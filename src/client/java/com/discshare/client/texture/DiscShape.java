package com.discshare.client.texture;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.resources.Identifier;

/**
 * A disc texture plus the side walls around its solid pixels, so the item has thickness from every angle
 * (like vanilla items) instead of being two paper-thin faces you can see through edge-on.
 *
 * Each wall is 13 floats: 4 corners (x,y,z) then normal (nx,ny,nz)... packed as
 * x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3, u,v, dir  (dir: 0=-x 1=+x 2=+y 3=-y)
 */
public record DiscShape(Identifier texture, float[] walls, int wallCount) {
	public static final float FRONT = 8.5F / 16F;
	public static final float BACK = 7.5F / 16F;

	public static DiscShape of(Identifier id, NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		boolean[] solid = new boolean[w * h];
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				solid[y * w + x] = (img.getPixel(x, y) >>> 24) > 16;

		float[] buf = new float[w * h * 4 * 15];
		int n = 0;
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				if (!solid[py * w + px]) continue;
				float x0 = px / (float) w, x1 = (px + 1) / (float) w;
				float yTop = 1 - py / (float) h, yBot = 1 - (py + 1) / (float) h; // image row 0 is the top
				float u = (px + 0.5F) / w, v = (py + 0.5F) / h;
				if (px == 0 || !solid[py * w + px - 1]) n = put(buf, n, x0, yBot, x0, yTop, true, u, v, 0);
				if (px == w - 1 || !solid[py * w + px + 1]) n = put(buf, n, x1, yBot, x1, yTop, true, u, v, 1);
				if (py == 0 || !solid[(py - 1) * w + px]) n = put(buf, n, x0, yTop, x1, yTop, false, u, v, 2);
				if (py == h - 1 || !solid[(py + 1) * w + px]) n = put(buf, n, x0, yBot, x1, yBot, false, u, v, 3);
			}
		}
		float[] walls = java.util.Arrays.copyOf(buf, n * 15);
		return new DiscShape(id, walls, n);
	}

	/** Adds one wall running from (ax,ay) to (bx,by) in the item plane, spanning BACK..FRONT in depth. */
	private static int put(float[] b, int n, float ax, float ay, float bx, float by, boolean vertical, float u, float v, int dir) {
		int o = n * 15;
		b[o] = ax; b[o + 1] = ay; b[o + 2] = BACK;
		b[o + 3] = bx; b[o + 4] = by; b[o + 5] = BACK;
		b[o + 6] = bx; b[o + 7] = by; b[o + 8] = FRONT;
		b[o + 9] = ax; b[o + 10] = ay; b[o + 11] = FRONT;
		b[o + 12] = u; b[o + 13] = v; b[o + 14] = dir;
		return n + 1;
	}
}
