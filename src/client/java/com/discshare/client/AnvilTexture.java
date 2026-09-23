package com.discshare.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;

/**
 * Adds a "Disc texture" button next to the anvil.
 * Put a song-tagged disc in the anvil, click it, pick a PNG: the image is uploaded and the disc's
 * name tag is swapped for a new [#code] that has both the song and the texture. Then take the disc out as usual.
 */
public final class AnvilTexture {
	private static final Pattern TAG = Pattern.compile("\\[(yt:|sc:|#)[^\\]]*]");
	private static final int MAX = 128;

	private AnvilTexture() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof AnvilScreen anvil)) return;
			EditBox nameBox = null;
			for (AbstractWidget w : Screens.getWidgets(screen)) {
				if (w instanceof EditBox e) {
					nameBox = e;
					break;
				}
			}
			if (nameBox == null) return;
			final EditBox box = nameBox;

			int left = (width - 176) / 2;
			int top = (height - 166) / 2;
			Button[] self = new Button[1];
			self[0] = Button.builder(Component.literal("Disc texture"), b -> pick(anvil, box, self[0]))
					// Centered just above the anvil window (the right side is often covered by JEI/REI).
					.bounds(left + (176 - 80) / 2, Math.max(2, top - 24), 80, 20)
					.tooltip(Tooltip.create(Component.literal(
							"Pick a PNG for this disc (square, up to 128x128; bigger images are shrunk). "
									+ "The disc must have a song tag like [yt:...] in its name.")))
					.build();
			Screens.getWidgets(screen).add(self[0]);
		});
	}

	private static void pick(AnvilScreen anvil, EditBox box, Button button) {
		// Find the song tag: in the name being typed, or already on the disc.
		String name = box.getValue();
		DiscTag tag = DiscTag.parse(name);
		if (tag == null) tag = DiscTag.fromStack(anvil.getMenu().getSlot(0).getItem());
		if (tag == null) {
			button.setMessage(Component.literal("Needs a song tag"));
			return;
		}
		final DiscTag songTag = tag;
		button.active = false;
		button.setMessage(Component.literal("Choosing..."));

		Thread t = new Thread(() -> {
			String result;
			try {
				String file = openDialog();
				if (file == null) {
					result = "Disc texture";
				} else {
					byte[] png = prepare(new File(file));
					String code = Backend.uploadDiscTexture(songTag, png).join();
					Minecraft.getInstance().execute(() -> {
						String current = box.getValue();
						String updated = TAG.matcher(current).find()
								? TAG.matcher(current).replaceFirst("[#" + code + "]")
								: (current.isBlank() ? "" : current.trim() + " ") + "[#" + code + "]";
						box.setValue(updated.length() > 50 ? "[#" + code + "]" : updated);
					});
					result = "Texture set ✔";
				}
			} catch (Exception e) {
				DiscShareClient.LOGGER.warn("Setting disc texture failed", e);
				result = "Failed: " + shortReason(e);
			}
			String msg = result;
			Minecraft.getInstance().execute(() -> {
				button.setMessage(Component.literal(msg));
				button.active = true;
			});
		}, "DiscShare texture picker");
		t.setDaemon(true);
		t.start();
	}

	private static String openDialog() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(1);
			filters.put(stack.UTF8("*.png"));
			filters.flip();
			return TinyFileDialogs.tinyfd_openFileDialog("Pick a disc texture", "", filters, "PNG images", false);
		}
	}

	/** Loads the image, crops it square, shrinks it to 128x128 if bigger, and returns PNG bytes. */
	private static byte[] prepare(File file) throws Exception {
		BufferedImage src = ImageIO.read(file);
		if (src == null) throw new IllegalArgumentException("not an image");
		int side = Math.min(src.getWidth(), src.getHeight());
		BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
		int out = Math.min(side, MAX);
		BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		// Keep pixel art crisp; smooth bigger photos when shrinking.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, side <= MAX
				? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
				: RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(square, 0, 0, out, out, null);
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bytes);
		return bytes.toByteArray();
	}

	private static String shortReason(Throwable e) {
		while (e.getCause() != null) e = e.getCause();
		String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return m.length() > 24 ? m.substring(0, 24) : m;
	}
}
