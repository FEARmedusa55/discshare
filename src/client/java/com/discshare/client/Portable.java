package com.discshare.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * Portable player: hold a tagged disc in your off hand and press the key (default P) to play it from yourself.
 * Other DiscShare players nearby hear it at normal jukebox range, following you around.
 * It stops when you press the key again or the disc leaves your off hand.
 *
 * Other players learn who's playing what from the backend (checked every 2 seconds).
 */
public final class Portable {
	private static final long POLL_MS = 2_000;
	private static final long REFRESH_MS = 10_000;

	private static KeyMapping key;

	/** What I'm playing. */
	private static DiscTag myTag;
	private static CustomDiscSound mySound;
	private static long lastRefresh;

	/** Portable songs from other players, by their UUID. */
	private static final Map<UUID, CustomDiscSound> OTHERS = new HashMap<>();
	private static long lastPoll;
	private static boolean polling;

	private Portable() {}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("discshare", "main"));
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.discshare.portable", GLFW.GLFW_KEY_P, category));
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		long now = System.currentTimeMillis();

		while (key.consumeClick()) {
			if (myTag != null) stopMine(mc, true);
			else startMine(mc);
		}

		// Stop if the disc left my off hand (or I swapped it for a different one).
		if (myTag != null) {
			DiscTag held = DiscTag.fromStack(mc.player.getOffhandItem());
			if (!myTag.equals(held) || mySound == null || mySound.isStopped()) stopMine(mc, true);
			else if (now - lastRefresh > REFRESH_MS) {
				lastRefresh = now;
				Backend.reportPortable(JukeboxManager.serverId(mc), mc.player.getUUID(), myTag);
			}
		}

		// Stop other players' songs right away if their disc left their off hand or they left.
		OTHERS.entrySet().removeIf(e -> {
			Player p = mc.level.getPlayerByUUID(e.getKey());
			CustomDiscSound s = e.getValue();
			if (p == null || s.isStopped() || !s.tag().equals(DiscTag.fromStack(p.getOffhandItem()))) {
				s.stopNow();
				mc.getSoundManager().stop(s);
				return true;
			}
			return false;
		});

		if (!polling && now - lastPoll > POLL_MS) {
			lastPoll = now;
			polling = true;
			Backend.queryPortable(JukeboxManager.serverId(mc)).whenComplete((list, err) -> mc.execute(() -> {
				polling = false;
				if (list != null) applyOthers(mc, list);
			}));
		}
	}

	private static void startMine(Minecraft mc) {
		DiscTag tag = DiscTag.fromStack(mc.player.getOffhandItem());
		if (tag == null) {
			mc.player.sendOverlayMessage(Component.literal("Hold a disc with a song tag in your off hand"));
			return;
		}
		myTag = tag;
		mySound = new CustomDiscSound(tag, mc.player.blockPosition(), System.currentTimeMillis()).following(mc.player);
		mc.getSoundManager().play(mySound);
		NowPlaying.show(tag);
		lastRefresh = System.currentTimeMillis();
		Backend.reportPortable(JukeboxManager.serverId(mc), mc.player.getUUID(), tag);
	}

	private static void stopMine(Minecraft mc, boolean tellBackend) {
		if (mySound != null) {
			mySound.stopNow();
			mc.getSoundManager().stop(mySound);
		}
		if (tellBackend && mc.player != null) Backend.reportPortable(JukeboxManager.serverId(mc), mc.player.getUUID(), null);
		myTag = null;
		mySound = null;
	}

	private static void applyOthers(Minecraft mc, java.util.List<Backend.PortableEntry> list) {
		if (mc.level == null || mc.player == null) return;
		java.util.Set<UUID> active = new java.util.HashSet<>();
		for (Backend.PortableEntry e : list) {
			if (e.uuid().equals(mc.player.getUUID())) continue; // that's me
			Player p = mc.level.getPlayerByUUID(e.uuid());
			if (!(p instanceof AbstractClientPlayer other)) continue; // not near me
			if (!e.tag().equals(DiscTag.fromStack(other.getOffhandItem()))) continue; // not holding it (anymore)
			active.add(e.uuid());
			CustomDiscSound current = OTHERS.get(e.uuid());
			if (current != null && current.tag().equals(e.tag())) continue; // already playing
			if (current != null) {
				current.stopNow();
				mc.getSoundManager().stop(current);
			}
			CustomDiscSound s = new CustomDiscSound(e.tag(), other.blockPosition(), System.currentTimeMillis() - e.elapsedMs())
					.following(other);
			OTHERS.put(e.uuid(), s);
			mc.getSoundManager().play(s);
			Prefetch.maybe(e.tag());
		}
		// Anyone the backend no longer lists has stopped.
		OTHERS.entrySet().removeIf(en -> {
			if (active.contains(en.getKey())) return false;
			en.getValue().stopNow();
			mc.getSoundManager().stop(en.getValue());
			return true;
		});
	}

	public static void clear() {
		Minecraft mc = Minecraft.getInstance();
		if (myTag != null) stopMine(mc, true);
		for (CustomDiscSound s : OTHERS.values()) s.stopNow();
		OTHERS.clear();
	}
}
