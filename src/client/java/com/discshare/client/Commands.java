package com.discshare.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/**
 * /discshare volume          shows the current volume
 * /discshare volume <0-100>  sets it (saved, applies within a couple of seconds)
 */
public final class Commands {
	private Commands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("discshare")
						.then(ClientCommands.literal("volume")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(Component.literal("DiscShare volume: " + DiscShareClient.CONFIG.volume + "%"));
									return 1;
								})
								.then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100))
										.executes(ctx -> {
											int v = IntegerArgumentType.getInteger(ctx, "percent");
											DiscShareClient.CONFIG.volume = v;
											DiscShareClient.CONFIG.save();
											ctx.getSource().sendFeedback(Component.literal("DiscShare volume set to " + v + "%"));
											return 1;
										})))));
	}
}
