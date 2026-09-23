package com.discshare.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Who on this server has DiscShare, so we can show a little disc icon next to their name.
 * Every modded player checks in with the backend every 30 seconds; the reply lists everyone
 * else on the same server who checked in recently.
 */
public final class Presence {
	private static final long CHECK_IN_MS = 30_000;
	private static final FontDescription ICON_FONT =
			new FontDescription.Resource(Identifier.fromNamespaceAndPath("discshare", "icons"));

	private static final Set<UUID> WITH_MOD = ConcurrentHashMap.newKeySet();
	private static long lastCheckIn = 0;
	private static String lastServer = null;

	private Presence() {}

	public static boolean hasMod(UUID id) {
		if (!DiscShareClient.CONFIG.showModIcons || id == null) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && id.equals(mc.player.getUUID())) return true;
		return WITH_MOD.contains(id);
	}

	/** Just the disc icon (uses our icon font, so nothing else may be appended *inside* it). */
	public static MutableComponent icon() {
		return Component.literal("\uE000").withStyle(s -> s.withFont(ICON_FONT));
	}

	public static Component decorate(UUID id, Component name) {
		if (name == null || !hasMod(id)) return name;
		// Siblings under an empty parent, so the name keeps its normal font.
		return Component.empty().append(icon()).append(Component.literal(" ")).append(name);
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !DiscShareClient.CONFIG.showModIcons) return;
		String server = JukeboxManager.serverId(mc);
		long now = System.currentTimeMillis();
		if (!server.equals(lastServer)) {
			WITH_MOD.clear();
			lastServer = server;
			lastCheckIn = 0;
		}
		if (now - lastCheckIn < CHECK_IN_MS) return;
		lastCheckIn = now;
		Backend.checkIn(server, mc.player.getUUID()).thenAccept(ids -> {
			if (ids == null) return;
			WITH_MOD.clear();
			WITH_MOD.addAll(ids);
		});
	}

	public static void clear() {
		WITH_MOD.clear();
		lastServer = null;
		lastCheckIn = 0;
	}
}
