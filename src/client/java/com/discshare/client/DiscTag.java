package com.discshare.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A song tag read from a disc's anvil name.
 *
 * Supported formats (anywhere in the name, so you can add a title too):
 *   [yt:dQw4w9WgXcQ]   a YouTube video ID
 *   [sc:artist/track]  a SoundCloud path
 *   [#abc123]          a short code created on your DiscShare backend
 *
 * The key is safe to use as a file name and a URL path segment.
 */
public record DiscTag(String key) {
	private static final Pattern YT = Pattern.compile("\\[yt:([A-Za-z0-9_-]{11})]");
	private static final Pattern SC = Pattern.compile("\\[sc:([A-Za-z0-9_-]+/[A-Za-z0-9_-]+)]");
	private static final Pattern CODE = Pattern.compile("\\[#([A-Za-z0-9]{3,16})]");

	/** The readable part of the last name seen for each disc key (tag removed), e.g. "Cool Song". */
	private static final java.util.Map<String, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

	/** "Cool Song" from "Cool Song [yt:abc]", or null if the name was only the tag. */
	public String displayName() {
		return NAMES.get(key);
	}

	public static DiscTag parse(String name) {
		DiscTag tag = parseKey(name);
		if (tag != null) {
			String rest = name.replaceAll("\\[(yt:|sc:|#)[^\\]]*]", "").trim();
			if (!rest.isEmpty()) NAMES.put(tag.key(), rest);
		}
		return tag;
	}

	private static DiscTag parseKey(String name) {
		if (name == null) return null;
		Matcher m = YT.matcher(name);
		if (m.find()) return new DiscTag("yt_" + m.group(1));
		m = SC.matcher(name);
		if (m.find()) return new DiscTag("sc_" + m.group(1).replace('/', '~'));
		m = CODE.matcher(name);
		if (m.find()) return new DiscTag("c_" + m.group(1));
		return null;
	}

	/** Returns the tag on a music disc, or null if it isn't a tagged disc. */
	public static DiscTag fromStack(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		if (!stack.has(DataComponents.JUKEBOX_PLAYABLE)) return null;
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name == null ? null : parse(name.getString());
	}
}
