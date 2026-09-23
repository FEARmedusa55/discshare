package com.discshare.client.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.discshare.client.DiscShareClient;

/**
 * Downloads and converts a song on this PC (instead of on the DiscShare server):
 *   yt-dlp gets the audio  ->  ffmpeg turns it into a small mono .ogg Minecraft can play.
 */
public final class LocalConverter {
	private static final int MAX_SECONDS = 12 * 60;

	private LocalConverter() {}

	/** @param source a web link (YouTube, SoundCloud, ...) or a local file to convert. */
	public static void convert(Tools.Found tools, String source, boolean isLocalFile, Path out) throws IOException, InterruptedException {
		Path work = Files.createTempDirectory(out.getParent(), "work_");
		try {
			Path input;
			if (isLocalFile) {
				input = Path.of(source);
			} else {
				List<String> cmd = new ArrayList<>(List.of(
						tools.ytDlp().toString(),
						"--no-playlist", "--no-progress",
						"-f", "bestaudio/best",
						"--match-filter", "duration <= " + MAX_SECONDS,
						"-o", work.resolve("src.%(ext)s").toString()));
				if (tools.deno() != null) {
					cmd.add("--js-runtimes");
					cmd.add("deno:" + tools.deno());
				}
				cmd.add(source);
				run(cmd, work, 300);
				try (Stream<Path> s = Files.list(work)) {
					input = s.filter(p -> p.getFileName().toString().startsWith("src."))
							.findFirst().orElseThrow(() -> new IOException("download failed or song too long"));
				}
			}

			Path tmp = work.resolve("out.ogg");
			// Mono so it sounds positional in-game, like a real jukebox.
			List<String> ff = List.of(tools.ffmpeg().toString(), "-y", "-hide_banner", "-loglevel", "error",
					"-i", input.toString(), "-vn", "-ac", "1", "-ar", "44100",
					"-c:a", "libvorbis", "-q:a", "3", "-t", String.valueOf(MAX_SECONDS), tmp.toString());
			try {
				run(ff, work, 300);
			} catch (IOException e) {
				// Some ffmpeg builds lack libvorbis: fall back to the built-in encoder (needs stereo).
				run(List.of(tools.ffmpeg().toString(), "-y", "-hide_banner", "-loglevel", "error",
						"-i", input.toString(), "-vn", "-ac", "2", "-ar", "44100",
						"-c:a", "vorbis", "-strict", "-2", "-t", String.valueOf(MAX_SECONDS), tmp.toString()), work, 300);
			}
			Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} finally {
			try (Stream<Path> s = Files.walk(work)) {
				s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			} catch (Exception ignored) {
			}
		}
	}

	private static void run(List<String> cmd, Path dir, int timeoutSeconds) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
		// Put the tools folder on PATH too, so yt-dlp can find deno/ffmpeg next to it.
		String sep = java.io.File.pathSeparator;
		pb.environment().merge("PATH", Tools.DIR.toString(), (old, add) -> add + sep + old);
		Process p = pb.start();
		// Read output on another thread so a stuck program can't hang us past the timeout.
		var buf = new java.io.ByteArrayOutputStream();
		Thread reader = new Thread(() -> {
			try {
				p.getInputStream().transferTo(buf);
			} catch (IOException ignored) {
			}
		}, "DiscShare tool output");
		reader.setDaemon(true);
		reader.start();
		boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		reader.join(2000);
		String output = buf.toString();
		if (!done) {
			p.destroyForcibly();
			throw new IOException(cmd.get(0) + " timed out");
		}
		if (p.exitValue() != 0) {
			String tail = output.length() > 600 ? output.substring(output.length() - 600) : output;
			DiscShareClient.LOGGER.warn("{} failed:\n{}", Path.of(cmd.get(0)).getFileName(), tail);
			throw new IOException(Path.of(cmd.get(0)).getFileName() + " failed (exit " + p.exitValue() + ")");
		}
	}
}
