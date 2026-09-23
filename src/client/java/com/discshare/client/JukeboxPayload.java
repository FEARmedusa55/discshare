package com.discshare.client;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.netty.buffer.ByteBuf;

/**
 * Sent by the DiscShare Helper server plugin on channel "discshare:jukebox".
 * Body: one Minecraft string (VarInt length + UTF-8) holding JSON:
 *   {"x":1,"y":64,"z":-3,"name":"Cool Song [yt:dQw4w9WgXcQ]","elapsedMs":1234}
 */
public record JukeboxPayload(String json) implements CustomPacketPayload {
	public static final Type<JukeboxPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath("discshare", "jukebox"));

	public static final StreamCodec<ByteBuf, JukeboxPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(JukeboxPayload::new, JukeboxPayload::json);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
