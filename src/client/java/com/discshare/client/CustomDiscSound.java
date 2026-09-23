package com.discshare.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.BufferUtils;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * A jukebox-style positional sound that streams a downloaded .ogg file.
 * The sound starts "playing" right away; audio begins once the download finishes,
 * skipping ahead so everyone stays roughly in sync.
 */
public class CustomDiscSound extends AbstractSoundInstance {
	private static final Identifier STREAM_ID = Identifier.fromNamespaceAndPath("discshare", "stream");

	private final DiscTag tag;
	/** System time (ms) the song should be considered to have started at. */
	private final long startedAtMs;
	private final long createdAtMs = System.currentTimeMillis();

	public CustomDiscSound(DiscTag tag, BlockPos jukebox, long startedAtMs) {
		super(STREAM_ID, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.tag = tag;
		this.startedAtMs = startedAtMs;
		this.x = jukebox.getX() + 0.5;
		this.y = jukebox.getY() + 0.5;
		this.z = jukebox.getZ() + 0.5;
		this.volume = 4.0F; // same as vanilla jukebox: audible ~64 blocks
		this.pitch = 1.0F;
		this.attenuation = SoundInstance.Attenuation.LINEAR;
		this.looping = false;
		this.relative = false;
	}

	public DiscTag tag() {
		return tag;
	}

	public long createdAtMs() {
		return createdAtMs;
	}

	/**
	 * Everyone starts the song at the same moment: startedAtMs + startDelayMs (3s by default).
	 * That gives every PC time to download and load it, so nobody misses the beginning.
	 * If loading takes longer than that (or you walked up mid-song), we skip ahead to stay in sync.
	 */
	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library, Identifier id, boolean repeatInstantly) {
		long playAtMs = startedAtMs + DiscShareClient.CONFIG.startDelayMs;
		return Backend.audio(tag).thenCompose(path -> {
			long wait = playAtMs - System.currentTimeMillis();
			if (wait <= 0) return CompletableFuture.completedFuture(path);
			// Ready early: hold the audio until the shared start moment.
			return CompletableFuture.supplyAsync(() -> path, CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS));
		}).thenApply(path -> {
			try {
				AudioStream ogg = new JOrbisAudioStream(new BufferedInputStream(Files.newInputStream(path)));
				long late = System.currentTimeMillis() - playAtMs;
				return late > 250 ? new SkippingStream(ogg, late) : ogg;
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}).whenComplete((s, err) -> {
			if (err != null) DiscShareClient.LOGGER.warn("Couldn't load audio for {}", tag.key(), err);
		});
	}

	/** Throws away the first N milliseconds of audio so late listeners hear the right part of the song. */
	private static final class SkippingStream implements AudioStream {
		private final AudioStream inner;
		private long bytesToSkip;

		SkippingStream(AudioStream inner, long skipMs) {
			this.inner = inner;
			AudioFormat f = inner.getFormat();
			long frames = (long) (f.getSampleRate() * (skipMs / 1000.0));
			this.bytesToSkip = frames * f.getFrameSize();
		}

		@Override
		public AudioFormat getFormat() {
			return inner.getFormat();
		}

		@Override
		public ByteBuffer read(int capacity) throws IOException {
			while (bytesToSkip > 0) {
				ByteBuffer b = inner.read(capacity);
				int n = b.remaining();
				if (n == 0) return b; // song already over
				if (n <= bytesToSkip) {
					bytesToSkip -= n;
					continue;
				}
				// Part of this chunk is past the skip point: return only that part.
				b.position(b.position() + (int) bytesToSkip);
				bytesToSkip = 0;
				ByteBuffer rest = BufferUtils.createByteBuffer(b.remaining());
				rest.put(b).flip();
				return rest;
			}
			return inner.read(capacity);
		}

		@Override
		public void close() throws IOException {
			inner.close();
		}
	}
}
