package com.discshare.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The "Now Playing: ..." pop-up above the hotbar.
 * Vanilla would show the original disc's name, so we hide that one and show the custom song's title.
 */
public final class NowPlaying {
	/** Set while we're showing our own message, so the Hud mixin lets it through. */
	static boolean showingOurs = false;
	/** Set when we replaced a vanilla disc sound; the vanilla pop-up that follows gets blocked. */
	static boolean blockNextVanilla = false;

	/** Song titles from the backend, by disc key. */
	private static final Map<String, String> TITLES = new ConcurrentHashMap<>();

	private NowPlaying() {}

	/** Show the pop-up for a custom disc. */
	public static void show(DiscTag tag) {
		// 1. Title from the backend (e.g. the YouTube video title), if we already have it.
		String title = TITLES.get(tag.key());
		if (title != null) {
			display(title);
			return;
		}
		// 2. Whatever the disc was named besides the tag, e.g. "Cool Song [yt:...]" -> "Cool Song".
		String name = tag.displayName();
		if (name != null) display(name);

		// 3. Ask the backend for the real title; update the pop-up if it arrives quickly.
		long asked = System.currentTimeMillis();
		Backend.info(tag).thenAccept(t -> {
			if (t == null || t.isBlank()) return;
			TITLES.put(tag.key(), t);
			if (name == null && System.currentTimeMillis() - asked < 4000) {
				Minecraft.getInstance().execute(() -> display(t));
			}
		});
		if (name == null) display("Custom disc");
	}

	private static void display(String title) {
		showingOurs = true;
		try {
			Minecraft.getInstance().gui.hud.setNowPlaying(Component.literal(title));
		} finally {
			showingOurs = false;
		}
	}

	/** @return true if this vanilla "Now Playing" call should be hidden. */
	public static boolean shouldBlockVanilla() {
		if (showingOurs) return false;
		if (blockNextVanilla) {
			blockNextVanilla = false;
			return true;
		}
		return false;
	}
}
