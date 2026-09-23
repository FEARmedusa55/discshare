package com.discshare.client.local;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.discshare.client.DiscShareClient;

/**
 * Finds (or downloads once) the programs used to convert songs on this PC:
 *   yt-dlp  - downloads the song
 *   deno    - yt-dlp needs it for YouTube
 *   ffmpeg  - converts to the .ogg format Minecraft plays
 *
 * Search order for each: path set in discshare.json, the discshare-tools folder,
 * anywhere on the system PATH, then other mods' folders inside .minecraft.
 * Anything still missing is downloaded into .minecraft/discshare-tools (Windows only for ffmpeg).
 */
public final class Tools {
	public record Found(Path ytDlp, Path deno, Path ffmpeg) {}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
	private static final boolean MAC = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
	private static final boolean ARM = System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("aarch64")
			|| System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("arm");
	private static final String EXE = WINDOWS ? ".exe" : "";

	public static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("discshare-tools");

	private static CompletableFuture<Found> ready;

	private Tools() {}

	/** Starts finding/downloading tools in the background (called at game start). Safe to call repeatedly. */
	public static synchronized CompletableFuture<Found> ensure() {
		if (ready == null || ready.isCompletedExceptionally()) {
			ready = CompletableFuture.supplyAsync(Tools::setUp);
		}
		return ready;
	}

	private static Found setUp() {
		try {
			Files.createDirectories(DIR);
			var cfg = DiscShareClient.CONFIG;

			Path ytDlp = find(cfg.ytDlpPath, List.of("yt-dlp" + EXE, "yt-dlp_linux", "yt-dlp_macos", "yt-dlp"));
			Path deno = find(cfg.denoPath, List.of("deno" + EXE));
			Path ffmpeg = find(cfg.ffmpegPath, List.of("ffmpeg" + EXE));

			boolean downloading = ytDlp == null || deno == null || ffmpeg == null;
			if (downloading) tell("DiscShare: downloading song tools (first time only, ~100 MB)...");

			if (ytDlp == null) ytDlp = downloadYtDlp();
			else if (ytDlp.startsWith(DIR)) updateYtDlp(ytDlp); // keep our own copy current; YouTube changes often
			if (deno == null) deno = downloadDeno();
			if (ffmpeg == null) ffmpeg = downloadFfmpeg();

			if (downloading) tell("DiscShare: song tools ready.");
			DiscShareClient.LOGGER.info("DiscShare tools: yt-dlp={} deno={} ffmpeg={}", ytDlp, deno, ffmpeg);
			return new Found(ytDlp, deno, ffmpeg);
		} catch (Exception e) {
			DiscShareClient.LOGGER.warn("Couldn't set up song tools; will use the server instead", e);
			tell("DiscShare: couldn't set up song tools (" + e.getMessage() + "). Using the server instead.");
			throw new RuntimeException(e);
		}
	}

	// ---------- finding ----------

	private static Path find(String configured, List<String> names) {
		if (configured != null && !configured.isBlank()) {
			Path p = Path.of(configured);
			if (Files.isRegularFile(p)) return p;
		}
		for (String n : names) {
			Path p = DIR.resolve(n);
			if (Files.isRegularFile(p)) return p;
		}
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(java.io.File.pathSeparator)) {
				for (String n : names) {
					try {
						Path p = Path.of(dir.trim(), n);
						if (Files.isRegularFile(p)) return p;
					} catch (Exception ignored) {
					}
				}
			}
		}
		return findInGameDir(names);
	}

	/** Other mods sometimes ship these programs; look a few folders deep inside .minecraft. */
	private static Path findInGameDir(List<String> names) {
		Set<String> skip = Set.of("saves", "mods", "resourcepacks", "shaderpacks", "screenshots", "logs",
				"crash-reports", "schematics", "discshare-cache", "backups");
		Path game = FabricLoader.getInstance().getGameDir();
		try (Stream<Path> s = Files.walk(game, 5)) {
			return s.filter(p -> {
						Path rel = game.relativize(p);
						return rel.getNameCount() == 0 || !skip.contains(rel.getName(0).toString());
					})
					.filter(p -> names.contains(p.getFileName().toString()))
					.filter(Files::isRegularFile)
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	// ---------- downloading ----------

	private static Path downloadYtDlp() throws IOException, InterruptedException {
		String asset = WINDOWS ? "yt-dlp.exe" : MAC ? "yt-dlp_macos" : ARM ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
		Path out = DIR.resolve(WINDOWS ? "yt-dlp.exe" : "yt-dlp");
		download("https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + asset, out);
		out.toFile().setExecutable(true);
		return out;
	}

	private static void updateYtDlp(Path ytDlp) {
		try {
			Process p = new ProcessBuilder(ytDlp.toString(), "-U").redirectErrorStream(true).start();
			p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
			p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			DiscShareClient.LOGGER.debug("yt-dlp self-update failed", e);
		}
	}

	private static Path downloadDeno() throws IOException, InterruptedException {
		String target = WINDOWS ? "x86_64-pc-windows-msvc"
				: MAC ? (ARM ? "aarch64-apple-darwin" : "x86_64-apple-darwin")
				: (ARM ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu");
		Path zip = DIR.resolve("deno.zip");
		download("https://github.com/denoland/deno/releases/latest/download/deno-" + target + ".zip", zip);
		unzip(zip, name -> name.equals("deno" + EXE) ? "deno" + EXE : null);
		Files.deleteIfExists(zip);
		Path out = DIR.resolve("deno" + EXE);
		out.toFile().setExecutable(true);
		return out;
	}

	private static Path downloadFfmpeg() throws IOException, InterruptedException {
		if (!WINDOWS) {
			throw new IOException("ffmpeg not found - install it (e.g. 'brew install ffmpeg' or your package manager)");
		}
		// Small "shared" build: ffmpeg.exe plus the DLLs it needs (~75 MB download).
		String asset = ARM ? "ffmpeg-master-latest-winarm64-lgpl-shared.zip" : "ffmpeg-master-latest-win64-lgpl-shared.zip";
		Path zip = DIR.resolve("ffmpeg.zip");
		download("https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/" + asset, zip);
		unzip(zip, name -> {
			int slash = name.lastIndexOf('/');
			String file = name.substring(slash + 1);
			if (!name.contains("/bin/")) return null;
			return file.equals("ffmpeg.exe") || file.endsWith(".dll") ? file : null;
		});
		Files.deleteIfExists(zip);
		return DIR.resolve("ffmpeg.exe");
	}

	private static void download(String url, Path out) throws IOException, InterruptedException {
		DiscShareClient.LOGGER.info("DiscShare: downloading {}", url);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
		HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) {
			res.body().close();
			throw new IOException("download failed (" + res.statusCode() + "): " + url);
		}
		Path tmp = out.resolveSibling(out.getFileName() + ".part");
		try (InputStream in = res.body()) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Extracts zip entries; the mapper returns the file name to save as, or null to skip. */
	private static void unzip(Path zip, java.util.function.Function<String, String> mapper) throws IOException {
		try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry e;
			while ((e = z.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				String target = mapper.apply(e.getName());
				if (target == null || target.contains("..") || target.contains("/") || target.contains("\\")) continue;
				Files.copy(z, DIR.resolve(target), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void tell(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
		});
	}
}
