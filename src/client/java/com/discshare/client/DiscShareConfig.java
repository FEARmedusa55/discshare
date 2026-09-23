package com.discshare.client;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Stored at .minecraft/config/discshare.json */
public class DiscShareConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Your DiscShare backend, e.g. "http://your-server-ip:8080". No trailing slash. */
	public String backendUrl = "https://disc.lumaria.us";
	/** Largest audio file the mod will download. */
	public int maxDownloadMB = 40;
	/**
	 * Songs start this long (ms) after the disc goes in, for everyone at once.
	 * Gives each PC time to load the song so nobody misses the beginning.
	 * Everyone on a server should use the same value to stay in sync.
	 */
	public long startDelayMs = 1500;
	/** Show a small disc icon next to the names of players who have DiscShare (tab list and name tags). */
	public boolean showModIcons = true;
	/** Bumped when defaults change so old config files get the new defaults. */
	public int configVersion = 3;
	/** How close (in blocks) another player must be to a jukebox to count as the one who inserted a disc. */
	public double guessRadius = 6.0;
	/** How long (ms) after a player stops holding a tagged disc it can still be matched to a jukebox. */
	public long guessWindowMs = 3000;

	public static DiscShareConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("discshare.json");
		DiscShareConfig cfg = new DiscShareConfig();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					DiscShareConfig read = GSON.fromJson(r, DiscShareConfig.class);
					if (read != null) cfg = read;
				}
			}
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(cfg, w);
			}
		} catch (Exception e) {
			DiscShareClient.LOGGER.warn("Couldn't read/write discshare.json, using defaults", e);
		}
		if (cfg.configVersion < 3) {
			if (cfg.startDelayMs == 3000) cfg.startDelayMs = 1500; // old default
			if (cfg.backendUrl.equals("http://localhost:8080")) cfg.backendUrl = "https://disc.lumaria.us"; // old default
			cfg.configVersion = 3;
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(cfg, w);
			} catch (Exception ignored) {
			}
		}
		if (cfg.backendUrl.endsWith("/")) cfg.backendUrl = cfg.backendUrl.substring(0, cfg.backendUrl.length() - 1);
		return cfg;
	}
}
