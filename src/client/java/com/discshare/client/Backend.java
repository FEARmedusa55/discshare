package com.discshare.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import com.discshare.client.local.LocalConverter;
import com.discshare.client.local.Tools;

/**
 * Talks to the DiscShare backend:
 *  - downloads converted audio (cached on disk)
 *  - tells the backend what's playing, so late arrivals / other modded players can sync
 */
public final class Backend {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("discshare-cache");
	private static final Map<String, CompletableFuture<Path>> DOWNLOADS = new ConcurrentHashMap<>();

	private Backend() {}

	/** What's playing at a jukebox, according to the backend. */
	public record NowPlaying(DiscTag tag, long elapsedMs) {}

	/**
	 * Get a disc's audio as a mono .ogg file (cached on disk).
	 * Normally converted right here on this PC; falls back to the server if that fails and serverFallback is on.
	 */
	public static CompletableFuture<Path> audio(DiscTag tag) {
		return DOWNLOADS.computeIfAbsent(tag.key(), k -> CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(CACHE);
				Path out = CACHE.resolve(k + ".ogg");
				if (Files.exists(out) && Files.size(out) > 0) return out;

				if (cfg().convertLocally) {
					try {
						convertHere(k, out);
						return out;
					} catch (Exception e) {
						DiscShareClient.LOGGER.warn("Converting {} on this PC failed", k, e);
						if (!cfg().serverFallback) throw e;
					}
				}
				downloadConverted(k, out);
				return out;
			} catch (Exception e) {
				DOWNLOADS.remove(k); // allow a retry next time
				throw new RuntimeException(e);
			}
		}));
	}

	/** Download + convert on this PC with yt-dlp/ffmpeg. */
	private static void convertHere(String k, Path out) throws Exception {
		Tools.Found tools = Tools.ensure().join();
		if (k.startsWith("yt_")) {
			LocalConverter.convert(tools, "https://www.youtube.com/watch?v=" + k.substring(3), false, out);
		} else if (k.startsWith("sc_")) {
			LocalConverter.convert(tools, "https://soundcloud.com/" + k.substring(3).replace('~', '/'), false, out);
		} else {
			// A [#code]: ask the backend what it points to (a link, or an uploaded file we fetch as-is).
			HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/source/" + k))
					.timeout(Duration.ofSeconds(15)).GET().build();
			HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) throw new IOException("Unknown code " + k + " (" + res.statusCode() + ")");
			JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
			if (o.has("url")) {
				LocalConverter.convert(tools, o.get("url").getAsString(), false, out);
			} else {
				Path raw = CACHE.resolve(k + ".src");
				fetch(cfg().backendUrl + o.get("file").getAsString(), raw, Duration.ofMinutes(3));
				try {
					LocalConverter.convert(tools, raw.toString(), true, out);
				} finally {
					Files.deleteIfExists(raw);
				}
			}
		}
	}

	/** Old way: let the server convert it and download the result. */
	private static void downloadConverted(String k, Path out) throws Exception {
		fetch(cfg().backendUrl + "/audio/" + k + ".ogg", out, Duration.ofMinutes(3));
	}

	private static void fetch(String url, Path out, Duration timeout) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
		HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
		if (res.statusCode() != 200) {
			res.body().close();
			throw new IOException("Backend returned " + res.statusCode() + " for " + url);
		}
		long max = cfg().maxDownloadMB * 1024L * 1024L;
		long len = res.headers().firstValueAsLong("Content-Length").orElse(-1);
		if (len > max) {
			res.body().close();
			throw new IOException("File too large: " + len + " bytes");
		}
		Path tmp = out.resolveSibling(out.getFileName() + ".part");
		try (InputStream in = new LimitedInputStream(res.body(), max)) {
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Download a disc's texture PNG. Completes with null if the disc has no texture. */
	public static CompletableFuture<byte[]> texture(DiscTag tag) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/texture/" + tag.key() + ".png"))
				.timeout(Duration.ofSeconds(15))
				.GET().build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(res -> res.statusCode() == 200 && res.body().length <= 512 * 1024 ? res.body() : null);
	}

	/** The song's title from the backend (e.g. the YouTube title), or null. */
	public static CompletableFuture<String> info(DiscTag tag) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/info/" + tag.key()))
				.timeout(Duration.ofSeconds(30))
				.GET().build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) return null;
					JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
					return o.has("title") ? o.get("title").getAsString() : null;
				})
				.exceptionally(e -> null);
	}

	/** Tell the backend we're on this server with the mod; completes with everyone else who is (or null on error). */
	public static CompletableFuture<java.util.List<java.util.UUID>> checkIn(String server, java.util.UUID me) {
		JsonObject body = new JsonObject();
		body.addProperty("server", server);
		body.addProperty("uuid", me.toString());
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/presence"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) return null;
					java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
					for (var e : JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("players")) {
						try {
							ids.add(java.util.UUID.fromString(e.getAsString()));
						} catch (IllegalArgumentException ignored) {
						}
					}
					return ids;
				})
				.exceptionally(e -> null);
	}

	/** Upload a texture for a disc; the backend makes a new [#code] with the same song plus this texture. */
	public static CompletableFuture<String> uploadDiscTexture(DiscTag tag, byte[] png) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/disc-texture?key=" + tag.key()))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "image/png")
				.PUT(HttpRequest.BodyPublishers.ofByteArray(png))
				.build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
			JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
			if (res.statusCode() != 200 || !o.has("code")) {
				throw new RuntimeException(o.has("error") ? o.get("error").getAsString() : "server error " + res.statusCode());
			}
			return o.get("code").getAsString();
		});
	}

	public record PortableEntry(java.util.UUID uuid, DiscTag tag, long elapsedMs) {}

	/** Tell the backend I started (tag) or stopped (null) my portable player. Fire and forget. */
	public static void reportPortable(String server, java.util.UUID me, DiscTag tag) {
		JsonObject body = new JsonObject();
		body.addProperty("server", server);
		body.addProperty("uuid", me.toString());
		if (tag != null) body.addProperty("disc", tag.key());
		else body.add("disc", com.google.gson.JsonNull.INSTANCE);
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/portable"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
	}

	/** Who on this server is playing a portable disc right now. */
	public static CompletableFuture<java.util.List<PortableEntry>> queryPortable(String server) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/portable?server=" + enc(server)))
				.timeout(Duration.ofSeconds(5))
				.GET().build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
			if (res.statusCode() != 200) return null;
			java.util.List<PortableEntry> out = new java.util.ArrayList<>();
			for (var el : JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("players")) {
				JsonObject o = el.getAsJsonObject();
				try {
					out.add(new PortableEntry(java.util.UUID.fromString(o.get("uuid").getAsString()),
							new DiscTag(o.get("disc").getAsString()), o.get("elapsedMs").getAsLong()));
				} catch (Exception ignored) {
				}
			}
			return out;
		}).exceptionally(e -> null);
	}

	/** Report that this client just put a tagged disc into a jukebox. Fire and forget. */
	public static void reportPlaying(String server, String dimension, BlockPos pos, DiscTag tag) {
		JsonObject body = new JsonObject();
		body.addProperty("server", server);
		body.addProperty("dim", dimension);
		body.addProperty("x", pos.getX());
		body.addProperty("y", pos.getY());
		body.addProperty("z", pos.getZ());
		body.addProperty("disc", tag.key());
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/playing"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
				.exceptionally(e -> {
					DiscShareClient.LOGGER.debug("reportPlaying failed", e);
					return null;
				});
	}

	/** Ask the backend what (if anything) is playing at a jukebox. Completes with null if nothing. */
	public static CompletableFuture<NowPlaying> queryPlaying(String server, String dimension, BlockPos pos) {
		String q = "server=" + enc(server) + "&dim=" + enc(dimension)
				+ "&x=" + pos.getX() + "&y=" + pos.getY() + "&z=" + pos.getZ();
		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg().backendUrl + "/api/playing?" + q))
				.timeout(Duration.ofSeconds(5))
				.GET().build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) return null;
					JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
					if (!o.has("disc")) return null;
					return new NowPlaying(new DiscTag(o.get("disc").getAsString()), o.get("elapsedMs").getAsLong());
				})
				.exceptionally(e -> null);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static DiscShareConfig cfg() {
		return DiscShareClient.CONFIG;
	}

	/** Stops reading after a byte limit so a bad server can't fill the disk. */
	private static final class LimitedInputStream extends InputStream {
		private final InputStream in;
		private long left;

		LimitedInputStream(InputStream in, long limit) {
			this.in = in;
			this.left = limit;
		}

		@Override
		public int read() throws IOException {
			if (left <= 0) throw new IOException("Download exceeded size limit");
			int b = in.read();
			if (b >= 0) left--;
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			if (left <= 0) throw new IOException("Download exceeded size limit");
			int n = in.read(buf, off, (int) Math.min(len, left));
			if (n > 0) left -= n;
			return n;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
