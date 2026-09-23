package com.discshare.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;

/**
 * Gets songs ready BEFORE the disc goes in a jukebox, so playback starts right away.
 * When a tagged disc shows up in your inventory or in anyone's hand, we download it in the background
 * (which also makes the backend convert it, if nobody has played it before).
 */
public final class Prefetch {
	/** Don't retry a failed song more than once a minute. */
	private static final long RETRY_MS = 60_000;
	private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();
	private static int inventoryScanTicks = 0;

	private Prefetch() {}

	public static void maybe(DiscTag tag) {
		if (tag == null) return;
		long now = System.currentTimeMillis();
		Long last = ASKED.get(tag.key());
		if (last != null && now - last < RETRY_MS) return;
		ASKED.put(tag.key(), now);
		Backend.audio(tag).whenComplete((path, err) -> {
			if (err != null) ASKED.put(tag.key(), System.currentTimeMillis()); // wait a minute before retrying
			else ASKED.put(tag.key(), Long.MAX_VALUE / 2); // cached; never ask again this session
		});
		Backend.info(tag); // also warm up the song title for the "Now Playing" pop-up
	}

	/** Every ~5 seconds, look through your own inventory for tagged discs. */
	public static void tick(Minecraft mc) {
		if (mc.player == null) return;
		if (++inventoryScanTicks < 100) return;
		inventoryScanTicks = 0;
		Inventory inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			maybe(DiscTag.fromStack(inv.getItem(i)));
		}
	}

	public static void clear() {
		ASKED.clear();
	}
}
