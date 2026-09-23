package com.discshare.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.BufferUtils;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
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
public class CustomDiscSound extends AbstractSoundInstance implements TickableSoundInstance {
	private static final Identifier STREAM_ID = Identifier.fromNamespaceAndPath("discshare", "stream");

	private final DiscTag tag;
	/** System time (ms) the song should be considered to have started at. */
	private final long startedAtMs;
	private final long createdAtMs = System.currentTimeMillis();
	/** If set, the sound follows this entity around (portable player). */
	private net.minecraft.world.entity.Entity follow;
	/** Set when the disc is taken out. Checked even while the song is still loading, so it never starts late. */
	private volatile boolean stopped = false;

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

	/** Stop this song for good, including if it's still downloading or waiting to start. */
	public void stopNow() {
		stopped = true;
	}

	@Override
	public boolean isStopped() {
		return stopped; // the sound engine checks this every tick and stops us
	}

	/** Make this sound follow an entity instead of staying at a jukebox. */
	public CustomDiscSound following(net.minecraft.world.entity.Entity entity) {
		this.follow = entity;
		moveToFollowed();
		return this;
	}

	private void moveToFollowed() {
		this.x = follow.getX();
		this.y = follow.getY() + 1.0;
		this.z = follow.getZ();
	}

	@Override
	public void tick() {
		if (follow == null) return;
		if (follow.isRemoved()) {
			stopped = true;
			return;
		}
		moveToFollowed();
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
			if (stopped) throw new CancellationException("disc removed while loading");
			try {
				AudioStream ogg = new JOrbisAudioStream(new BufferedInputStream(Files.newInputStream(path)));
				long late = System.currentTimeMillis() - playAtMs;
				AudioStream stream = late > 250 ? new SkippingStream(ogg, late) : ogg;
				return (AudioStream) new VolumeStream(stream);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}).whenComplete((s, err) -> {
			if (err != null && !stopped) DiscShareClient.LOGGER.warn("Couldn't load audio for {}", tag.key(), err);
		});
	}

	/**
	 * Applies the DiscShare volume (/discshare volume) by scaling the audio samples.
	 * Changing the sound's own volume would shrink how far away it can be heard instead of making it quieter.
	 * Reads the setting on every chunk, so changes apply within a couple of seconds.
	 */
	private static final class VolumeStream implements AudioStream {
		private final AudioStream inner;
		private final boolean pcm16;

		VolumeStream(AudioStream inner) {
			this.inner = inner;
			AudioFormat f = inner.getFormat();
			this.pcm16 = f.getSampleSizeInBits() == 16 && f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
		}

		@Override
		public AudioFormat getFormat() {
			return inner.getFormat();
		}

		@Override
		public ByteBuffer read(int capacity) throws IOException {
			ByteBuffer b = inner.read(capacity);
			float vol = DiscShareClient.CONFIG.volume / 100f;
			if (!pcm16 || vol >= 0.999f) return b;
			ByteOrder order = inner.getFormat().isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
			ByteBuffer v = b.duplicate().order(order);
			for (int i = v.position(); i + 1 < v.limit(); i += 2) {
				v.putShort(i, (short) Math.round(v.getShort(i) * vol));
			}
			return b;
		}

		@Override
		public void close() throws IOException {
			inner.close();
		}
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
