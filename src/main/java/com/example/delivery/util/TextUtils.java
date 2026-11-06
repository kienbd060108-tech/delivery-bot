package com.example.delivery.util;

import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Utility methods for stripping Minecraft formatting codes, normalizing Vietnamese strings,
 * and sending local client status messages.
 */
public final class TextUtils {
	private static final Pattern FORMATTING_CODE_PATTERN = Pattern.compile("(?i)§[0-9A-FK-ORX]");

	private TextUtils() {
	}

	/**
	 * Removes all Minecraft {@code §} formatting codes and trims surrounding whitespace.
	 */
	public static String stripFormatting(String input) {
		if (input == null || input.isEmpty()) {
			return "";
		}
		String stripped = FORMATTING_CODE_PATTERN.matcher(input).replaceAll("");
		String fromChatFormatting = ChatFormatting.stripFormatting(stripped);
		return (fromChatFormatting != null ? fromChatFormatting : stripped).trim();
	}

	public static String stripFormatting(Component component) {
		if (component == null) {
			return "";
		}
		return stripFormatting(component.getString());
	}

	public static String normalizeLower(String input) {
		return stripFormatting(input).toLowerCase(Locale.ROOT);
	}

	public static boolean containsIgnoreCase(String text, String needle) {
		if (text == null || needle == null) {
			return false;
		}
		return normalizeLower(text).contains(normalizeLower(needle));
	}

	public static void sendClientMessage(Minecraft mc, String message, ChatFormatting color) {
		if (mc != null && mc.player != null) {
			Component prefix = Component.literal("[DeliveryBot] ").withStyle(ChatFormatting.GOLD);
			Component body = Component.literal(message).withStyle(color);
			mc.player.sendSystemMessage(prefix.copy().append(body));
		}
	}
}