package com.discshare.client.texture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import com.discshare.client.Backend;
import com.discshare.client.DiscShareClient;
import com.discshare.client.DiscTag;

/**
 * Downloads custom disc textures from the backend and turns them into game textures.
 * Discs without a texture keep their normal look.
 */
public final class DiscTextures {
	/** How long to wait before asking again about a disc that had no texture. */
	private static final long RETRY_MS = 60_000;

	private sealed interface State permits Loading, Ready, Missing {}
	private record Loading() implements State {}
	private record Ready(Identifier id, DiscShape shape) implements State {}
	private record Missing(long sinceMs) implements State {}

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();

	private DiscTextures() {}

	/** The texture to draw for this disc, or null if it has none (yet). Starts a download if needed. */
	public static Identifier get(ItemStack stack) {
		DiscTag tag = DiscTag.fromStack(stack);
		if (tag == null) return null;
		State s = STATES.get(tag.key());
		if (s instanceof Ready r) return r.id();
		if (s instanceof Loading) return null;
		if (s instanceof Missing m && System.currentTimeMillis() - m.sinceMs() < RETRY_MS) return null;
		load(tag);
		return null;
	}

	private static void load(DiscTag tag) {
		String key = tag.key();
		STATES.put(key, new Loading());
		Backend.texture(tag).whenComplete((bytes, err) -> {
			if (err != null || bytes == null) {
				STATES.put(key, new Missing(System.currentTimeMillis()));
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				try {
					NativeImage img = NativeImage.read(bytes);
					if (img.getWidth() > 128 || img.getHeight() > 128) {
						img.close();
						throw new IllegalArgumentException("texture larger than 128x128");
					}
					Identifier id = Identifier.fromNamespaceAndPath("discshare", "disc/" + key.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
					DiscShape shape = DiscShape.of(id, img); // read pixels before handing the image over
					mc.getTextureManager().register(id, new DynamicTexture(() -> "DiscShare " + key, img));
					STATES.put(key, new Ready(id, shape));
				} catch (Exception e) {
					DiscShareClient.LOGGER.warn("Bad texture for {}", key, e);
					STATES.put(key, new Missing(System.currentTimeMillis()));
				}
			});
		});
	}

	/** The texture plus its outline (for drawing item edges), or null if none (yet). */
	public static DiscShape shape(ItemStack stack) {
		if (get(stack) == null) return null;
		DiscTag tag = DiscTag.fromStack(stack);
		return STATES.get(tag.key()) instanceof Ready r ? r.shape() : null;
	}

	/** Forget everything (e.g. after changing servers) so textures are fetched fresh. */
	public static void clear() {
		Minecraft mc = Minecraft.getInstance();
		for (State s : STATES.values()) {
			if (s instanceof Ready r) mc.getTextureManager().release(r.id());
		}
		STATES.clear();
	}
}
