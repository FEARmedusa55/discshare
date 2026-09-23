package com.discshare.client.texture;

import com.mojang.serialization.MapCodec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Item model condition "discshare:has_texture": true when this disc has a downloaded custom texture. */
public record HasDiscTexture() implements ConditionalItemModelProperty {
	public static final MapCodec<HasDiscTexture> MAP_CODEC = MapCodec.unit(new HasDiscTexture());

	@Override
	public boolean get(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext context) {
		return DiscTextures.get(stack) != null;
	}

	@Override
	public MapCodec<HasDiscTexture> type() {
		return MAP_CODEC;
	}
}
