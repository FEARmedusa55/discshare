package com.discshare.client.texture;

import java.util.function.Consumer;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Item model type "discshare:disc": draws the disc's downloaded texture as a flat item,
 * the same size and position as a normal 16x16 item sprite.
 */
public class DiscSpecialRenderer implements SpecialModelRenderer<Identifier> {
	// Normal flat items sit 1 pixel thick around the middle of the block space.
	private static final float FRONT = 8.5F / 16F;
	private static final float BACK = 7.5F / 16F;

	@Override
	public Identifier extractArgument(ItemStack stack) {
		return DiscTextures.get(stack);
	}

	@Override
	public void submit(Identifier texture, PoseStack poseStack, SubmitNodeCollector collector,
			int light, int overlay, boolean hasFoil, int outlineColor) {
		if (texture == null) return;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, vc) -> {
			// Front (facing the viewer in the inventory)
			vertex(vc, pose, 0, 0, FRONT, 0, 1, light, overlay, 1);
			vertex(vc, pose, 1, 0, FRONT, 1, 1, light, overlay, 1);
			vertex(vc, pose, 1, 1, FRONT, 1, 0, light, overlay, 1);
			vertex(vc, pose, 0, 1, FRONT, 0, 0, light, overlay, 1);
			// Back (mirrored, like vanilla items)
			vertex(vc, pose, 0, 1, BACK, 0, 0, light, overlay, -1);
			vertex(vc, pose, 1, 1, BACK, 1, 0, light, overlay, -1);
			vertex(vc, pose, 1, 0, BACK, 1, 1, light, overlay, -1);
			vertex(vc, pose, 0, 0, BACK, 0, 1, light, overlay, -1);
		});
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
			float u, float v, int light, int overlay, float nz) {
		vc.addVertex(pose, x, y, z)
				.setColor(-1)
				.setUv(u, v)
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, 0, 0, nz);
	}

	@Override
	public void getExtents(Consumer<Vector3fc> out) {
		for (float x : new float[] {0, 1})
			for (float y : new float[] {0, 1})
				for (float z : new float[] {BACK, FRONT})
					out.accept(new Vector3f(x, y, z));
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<Identifier> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<Identifier> bake(SpecialModelRenderer.BakingContext context) {
			return new DiscSpecialRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
