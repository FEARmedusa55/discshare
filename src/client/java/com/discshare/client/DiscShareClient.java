package com.discshare.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import com.discshare.client.texture.DiscSpecialRenderer;
import com.discshare.client.texture.DiscTextures;
import com.discshare.client.texture.HasDiscTexture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

public class DiscShareClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("DiscShare");
	public static DiscShareConfig CONFIG;

	@Override
	public void onInitializeClient() {
		CONFIG = DiscShareConfig.load();

		// Track who is holding tagged discs.
		ClientTickEvents.END_CLIENT_TICK.register(JukeboxManager::tick);
		// Check in with the backend so other DiscShare users can see our icon (and we can see theirs).
		ClientTickEvents.END_CLIENT_TICK.register(Presence::tick);
		// Download songs for tagged discs in your inventory ahead of time.
		ClientTickEvents.END_CLIENT_TICK.register(Prefetch::tick);

		// Remember when *we* put a tagged disc into a jukebox.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level instanceof ClientLevel && level.getBlockState(hit.getBlockPos()).is(Blocks.JUKEBOX)) {
				DiscTag tag = DiscTag.fromStack(player.getItemInHand(hand));
				if (tag != null) JukeboxManager.onLocalUseDisc(hit.getBlockPos(), tag);
			}
			return InteractionResult.PASS;
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			JukeboxManager.clear();
			Presence.clear();
			Prefetch.clear();
			client.execute(DiscTextures::clear);
		});

		// Custom disc textures: item model types used by assets/minecraft/items/music_disc_*.json
		SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath("discshare", "disc"), DiscSpecialRenderer.Unbaked.MAP_CODEC);
		ConditionalItemModelProperties.ID_MAPPER.put(Identifier.fromNamespaceAndPath("discshare", "has_texture"), HasDiscTexture.MAP_CODEC);

		// Exact jukebox info from the DiscShare Helper plugin, when the server has it.
		PayloadTypeRegistry.clientboundPlay().register(JukeboxPayload.TYPE, JukeboxPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(JukeboxPayload.TYPE, (payload, context) -> {
			try {
				JsonObject o = JsonParser.parseString(payload.json()).getAsJsonObject();
				BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				String name = o.get("name").getAsString();
				long elapsed = o.has("elapsedMs") ? o.get("elapsedMs").getAsLong() : 0;
				Minecraft.getInstance().execute(() -> JukeboxManager.onServerPlay(pos, name, elapsed));
			} catch (Exception e) {
				LOGGER.warn("Bad jukebox message from server: {}", payload.json(), e);
			}
		});

		LOGGER.info("DiscShare ready, backend: {}", CONFIG.backendUrl);
	}
}
