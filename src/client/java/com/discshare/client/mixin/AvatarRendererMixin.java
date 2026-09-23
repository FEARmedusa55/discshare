package com.discshare.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

import com.discshare.client.Presence;

/** Disc icon in front of DiscShare users' name tags above their heads. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void discshare$addIcon(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (state.nameTag != null) state.nameTag = Presence.decorate(entity.getUUID(), state.nameTag);
	}
}
