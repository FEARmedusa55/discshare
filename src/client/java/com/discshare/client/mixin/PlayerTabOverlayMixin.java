package com.discshare.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import com.discshare.client.Presence;

/** Disc icon next to DiscShare users in the tab list. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
			at = @At("RETURN"), cancellable = true)
	private void discshare$addIcon(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		Component name = cir.getReturnValue();
		Component decorated = Presence.decorate(info.getProfile().id(), name);
		if (decorated != name) cir.setReturnValue(decorated);
	}
}
