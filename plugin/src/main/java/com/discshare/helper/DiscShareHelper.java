package com.discshare.helper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Tells DiscShare clients which renamed disc is in each nearby jukebox.
 *
 * Only players whose client registered the "discshare:jukebox" channel (i.e. have the mod)
 * get messages, so vanilla players are unaffected. Only discs whose name contains a
 * DiscShare tag like [yt:...], [sc:...] or [#...] are ever sent.
 */
public final class DiscShareHelper extends JavaPlugin implements Listener {
	static final String CHANNEL = "discshare:jukebox";
	/** A bit more than the 64-block range a jukebox can be heard from. */
	static final double RANGE = 72;
	static final int SCAN_CHUNK_RADIUS = 5;
	static final Pattern TAGGED = Pattern.compile("\\[(yt:|sc:|#)");

	private record Key(UUID world, int x, int y, int z) {
		static Key of(Block b) {
			return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
		}
	}

	private static final class Playing {
		final String name;
		final long startedAtMs;
		final Set<UUID> told = new HashSet<>();

		Playing(String name, long startedAtMs) {
			this.name = name;
			this.startedAtMs = startedAtMs;
		}
	}

	private final Map<Key, Playing> playing = new HashMap<>();

	@Override
	public void onEnable() {
		getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);
		// Every 2 seconds: find jukeboxes near players and sync anyone who walked into range.
		Bukkit.getScheduler().runTaskTimer(this, this::scan, 40L, 40L);
	}

	// ---- instant detection ----

	@EventHandler(priority = EventPriority.MONITOR)
	public void onInteract(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		Block b = e.getClickedBlock();
		if (b.getType() != Material.JUKEBOX) return;
		// The disc goes in during this tick; look next tick.
		Bukkit.getScheduler().runTaskLater(this, () -> check(b, true), 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHopper(InventoryMoveItemEvent e) {
		// Hopper putting a disc in, or pulling one out.
		var inv = e.getDestination().getType() == InventoryType.JUKEBOX ? e.getDestination()
				: e.getSource().getType() == InventoryType.JUKEBOX ? e.getSource() : null;
		if (inv == null) return;
		Location loc = inv.getLocation();
		if (loc == null) return;
		Block b = loc.getBlock();
		Bukkit.getScheduler().runTaskLater(this, () -> check(b, true), 1L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		UUID id = e.getPlayer().getUniqueId();
		for (Playing p : playing.values()) p.told.remove(id);
	}

	// ---- periodic scan ----

	private void scan() {
		Set<Key> seen = new HashSet<>();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (!player.getListeningPluginChannels().contains(CHANNEL)) continue;
			World w = player.getWorld();
			int pcx = player.getLocation().getBlockX() >> 4;
			int pcz = player.getLocation().getBlockZ() >> 4;
			for (int cx = pcx - SCAN_CHUNK_RADIUS; cx <= pcx + SCAN_CHUNK_RADIUS; cx++) {
				for (int cz = pcz - SCAN_CHUNK_RADIUS; cz <= pcz + SCAN_CHUNK_RADIUS; cz++) {
					if (!w.isChunkLoaded(cx, cz)) continue;
					Chunk chunk = w.getChunkAt(cx, cz);
					for (BlockState state : chunk.getTileEntities(b -> b.getType() == Material.JUKEBOX, false)) {
						if (seen.add(Key.of(state.getBlock()))) check(state.getBlock(), false);
					}
				}
			}
		}
		// Re-check jukeboxes we know about that nobody is near (so they get cleaned up).
		for (Iterator<Map.Entry<Key, Playing>> it = playing.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Key, Playing> e = it.next();
			if (seen.contains(e.getKey())) continue;
			World w = Bukkit.getWorld(e.getKey().world());
			if (w == null || !w.isChunkLoaded(e.getKey().x() >> 4, e.getKey().z() >> 4)) {
				it.remove();
				continue;
			}
			Block b = w.getBlockAt(e.getKey().x(), e.getKey().y(), e.getKey().z());
			if (discName(b) == null) it.remove();
		}
	}

	// ---- core ----

	/** Looks at one jukebox, updates what we know, and tells nearby modded players. */
	private void check(Block b, boolean justInserted) {
		Key key = Key.of(b);
		String name = discName(b);
		if (name == null) {
			playing.remove(key);
			return;
		}
		Playing p = playing.get(key);
		if (p == null || !p.name.equals(name)) {
			// New disc. If we only found it by scanning, we don't know exactly when it started; "now" is close enough.
			p = new Playing(name, System.currentTimeMillis());
			playing.put(key, p);
		}
		sendNearby(b, p);
	}

	/** The tagged disc name in this jukebox, or null if there isn't one. */
	private static String discName(Block b) {
		if (b.getType() != Material.JUKEBOX) return null;
		if (!(b.getState(false) instanceof Jukebox jukebox)) return null;
		ItemStack record = jukebox.getRecord();
		if (record == null || record.getType().isAir() || !record.hasItemMeta()) return null;
		ItemMeta meta = record.getItemMeta();
		if (!meta.hasDisplayName()) return null;
		String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
		return TAGGED.matcher(name).find() ? name : null;
	}

	private void sendNearby(Block b, Playing p) {
		Location center = b.getLocation().add(0.5, 0.5, 0.5);
		byte[] msg = null;
		for (Player player : b.getWorld().getPlayers()) {
			UUID id = player.getUniqueId();
			boolean inRange = player.getLocation().distanceSquared(center) <= RANGE * RANGE;
			if (!inRange) {
				p.told.remove(id); // walking back in later re-sends, with the right song position
				continue;
			}
			if (p.told.contains(id) || !player.getListeningPluginChannels().contains(CHANNEL)) continue;
			if (msg == null) msg = encode(b, p);
			player.sendPluginMessage(this, CHANNEL, msg);
			p.told.add(id);
		}
	}

	private static byte[] encode(Block b, Playing p) {
		long elapsed = System.currentTimeMillis() - p.startedAtMs;
		String json = "{\"x\":" + b.getX() + ",\"y\":" + b.getY() + ",\"z\":" + b.getZ()
				+ ",\"name\":\"" + escape(p.name) + "\",\"elapsedMs\":" + elapsed + "}";
		byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(utf8.length + 5);
		// Minecraft string = VarInt byte length + UTF-8 bytes.
		int v = utf8.length;
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.write(v);
		out.writeBytes(utf8);
		return out.toByteArray();
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.toString();
	}
}
