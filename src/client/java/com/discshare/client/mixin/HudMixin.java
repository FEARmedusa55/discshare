package com.discshare.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;

import com.discshare.client.NowPlaying;

/** Hides vanilla's "Now Playing: C418 - cat" when that disc is actually playing a custom song. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "setNowPlaying(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
	private void discshare$onSetNowPlaying(Component text, CallbackInfo ci) {
		if (NowPlaying.shouldBlockVanilla()) ci.cancel();
	}
}
