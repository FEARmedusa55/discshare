package com.discshare.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides which jukebox sounds get replaced with custom audio.
 *
 * How a jukebox is matched to a disc, best first:
 *  1. The DiscShare Helper plugin on the server says what's in it (exact; hoppers, redstone and late arrivals all work).
 *  2. You put the tagged disc in yourself (certain; also reported to the backend).
 *  3. Another player holding a tagged disc was standing next to the jukebox a moment ago (a good guess).
 *  4. The backend says a modded player put that disc there (covers arriving late).
 * If none match, the vanilla disc plays as normal. A later answer from the plugin always wins.
 */
public final class JukeboxManager {
	private record Pending(DiscTag tag, long timeMs) {}
	private record Seen(DiscTag tag, long timeMs) {}
	private record Known(DiscTag tag, long startedAtMs, long receivedAtMs) {}

	/** Jukeboxes the local player just used a tagged disc on. */
	private static final Map<BlockPos, Pending> LOCAL_PENDING = new HashMap<>();
	/** Last time each player was seen holding a tagged disc. */
	private static final Map<UUID, Seen> LAST_HELD = new HashMap<>();
	/** What the server plugin told us is in each jukebox. */
	private static final Map<BlockPos, Known> SERVER_KNOWN = new HashMap<>();
	/** The latest vanilla jukebox sound at each position (so it can be swapped out later). */
	private static final Map<BlockPos, SoundInstance> VANILLA_AT = new HashMap<>();
	/** Jukebox position -> our replacement sound playing there. */
	private static final Map<BlockPos, CustomDiscSound> ACTIVE = new HashMap<>();
	/** Vanilla jukebox sounds still waiting to hear back from the backend. */
	private static final Set<SoundInstance> ASKING_BACKEND = new HashSet<>();

	private JukeboxManager() {}

	// ---- called from DiscShareClient ----

	public static void onLocalUseDisc(BlockPos jukebox, DiscTag tag) {
		LOCAL_PENDING.put(jukebox.immutable(), new Pending(tag, System.currentTimeMillis()));
	}

	/** The server plugin says this jukebox holds a disc with this name. */
	public static void onServerPlay(BlockPos pos, String discName, long elapsedMs) {
		DiscTag tag = DiscTag.parse(discName);
		if (tag == null) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;

		pos = pos.immutable();
		long now = System.currentTimeMillis();
		SERVER_KNOWN.put(pos, new Known(tag, now - elapsedMs, now));

		CustomDiscSound current = ACTIVE.get(pos);
		if (current != null && current.tag().equals(tag)) return; // already playing the right song

		SoundInstance vanilla = VANILLA_AT.get(pos);
		if (vanilla != null) {
			ASKING_BACKEND.remove(vanilla);
			mc.getSoundManager().stop(vanilla);
		}
		start(pos, tag, now - elapsedMs);
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) return;
		long now = System.currentTimeMillis();
		for (AbstractClientPlayer p : mc.level.players()) {
			DiscTag tag = DiscTag.fromStack(p.getMainHandItem());
			if (tag == null) tag = DiscTag.fromStack(p.getOffhandItem());
			if (tag != null) {
				LAST_HELD.put(p.getUUID(), new Seen(tag, now));
				Prefetch.maybe(tag); // someone's holding it: get the song ready before it goes in
			}
		}
		// Forget stale entries.
		long window = DiscShareClient.CONFIG.guessWindowMs;
		LAST_HELD.values().removeIf(s -> now - s.timeMs() > window * 4);
		LOCAL_PENDING.values().removeIf(p -> now - p.timeMs() > 5000);
		SERVER_KNOWN.entrySet().removeIf(e -> now - e.getValue().receivedAtMs() > 2000 && !hasDisc(mc, e.getKey()));
		VANILLA_AT.keySet().removeIf(pos -> !ACTIVE.containsKey(pos) && !hasDisc(mc, pos));

		// Keep custom songs going until the disc is actually taken out (or the jukebox is gone / unloaded).
		// We don't trust vanilla's stop: the server stops the jukebox when the *vanilla* disc would end
		// (MC-260346), which would cut off longer custom songs.
		var sm = mc.getSoundManager();
		ACTIVE.entrySet().removeIf(e -> {
			CustomDiscSound s = e.getValue();
			// Give the block update (HAS_RECORD) time to arrive after a disc goes in.
			if (now - s.createdAtMs() < 2000) return false;
			if (!hasDisc(mc, e.getKey())) {
				s.stopNow(); // also cancels it if it's still loading
				sm.stop(s);
				VANILLA_AT.remove(e.getKey());
				return true;
			}
			return false;
		});
	}

	private static boolean hasDisc(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD);
	}

	public static void clear() {
		LOCAL_PENDING.clear();
		LAST_HELD.clear();
		SERVER_KNOWN.clear();
		VANILLA_AT.clear();
		ACTIVE.clear();
		ASKING_BACKEND.clear();
	}

	// ---- called from SoundManagerMixin ----

	/** @return true to cancel the vanilla sound */
	public static boolean onPlay(SoundInstance sound) {
		if (sound instanceof CustomDiscSound) return false;
		if (sound.getSource() != SoundSource.RECORDS) return false;
		if (!sound.getIdentifier().getPath().startsWith("music_disc.")) return false;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;

		BlockPos pos = BlockPos.containing(sound.getX(), sound.getY(), sound.getZ());
		long now = System.currentTimeMillis();
		VANILLA_AT.put(pos, sound);

		// 1. The server plugin already told us.
		Known known = SERVER_KNOWN.get(pos);
		if (known != null) {
			CustomDiscSound current = ACTIVE.get(pos);
			if (current == null || !current.tag().equals(known.tag())) start(pos, known.tag(), known.startedAtMs());
			NowPlaying.blockNextVanilla = true;
			return true;
		}

		// 2. We inserted it ourselves.
		Pending mine = LOCAL_PENDING.remove(pos);
		if (mine != null && now - mine.timeMs() < 5000) {
			Backend.reportPlaying(serverId(mc), dimensionId(mc), pos, mine.tag());
			start(pos, mine.tag(), now);
			NowPlaying.blockNextVanilla = true;
			return true;
		}

		// 3. Someone holding a tagged disc was right here a moment ago.
		DiscTag guess = guess(mc, pos, now);
		if (guess != null) {
			start(pos, guess, now);
			NowPlaying.blockNextVanilla = true;
			return true;
		}

		// 4. Ask the backend. Let vanilla play meanwhile; swap if the backend knows this jukebox.
		ASKING_BACKEND.add(sound);
		Backend.queryPlaying(serverId(mc), dimensionId(mc), pos).thenAccept(np -> mc.execute(() -> {
			if (!ASKING_BACKEND.remove(sound) || np == null) return;
			mc.getSoundManager().stop(sound);
			start(pos, np.tag(), System.currentTimeMillis() - np.elapsedMs());
		}));
		return false;
	}

	public static void onStop(SoundInstance sound) {
		// Custom songs are stopped from tick() once the disc is really gone; see the note there.
		ASKING_BACKEND.remove(sound);
	}

	// ---- helpers ----

	private static void start(BlockPos pos, DiscTag tag, long startedAtMs) {
		var sm = Minecraft.getInstance().getSoundManager();
		CustomDiscSound old = ACTIVE.remove(pos);
		if (old != null) {
			old.stopNow();
			sm.stop(old);
		} // a different disc went into the same jukebox
		CustomDiscSound custom = new CustomDiscSound(tag, pos, startedAtMs);
		ACTIVE.put(pos.immutable(), custom);
		sm.play(custom);
		var player = Minecraft.getInstance().player;
		if (player != null && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64 * 64) {
			NowPlaying.show(tag);
		}
		DiscShareClient.LOGGER.info("Playing {} at {}", tag.key(), pos.toShortString());
	}

	private static DiscTag guess(Minecraft mc, BlockPos pos, long now) {
		double r = DiscShareClient.CONFIG.guessRadius;
		long window = DiscShareClient.CONFIG.guessWindowMs;
		double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;

		DiscTag best = null;
		long bestTime = Long.MIN_VALUE;
		for (AbstractClientPlayer p : mc.level.players()) {
			Seen s = LAST_HELD.get(p.getUUID());
			if (s == null || now - s.timeMs() > window) continue;
			if (p.distanceToSqr(cx, cy, cz) > r * r) continue;
			if (s.timeMs() > bestTime) {
				best = s.tag();
				bestTime = s.timeMs();
			}
		}
		return best;
	}

	static String serverId(Minecraft mc) {
		ServerData sd = mc.getCurrentServer();
		return sd != null ? sd.ip.toLowerCase() : "singleplayer";
	}

	static String dimensionId(Minecraft mc) {
		return mc.level.dimension().toString();
	}
}
