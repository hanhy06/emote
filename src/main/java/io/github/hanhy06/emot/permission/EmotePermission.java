package io.github.hanhy06.emot.permission;

import java.util.Locale;

public final class EmotePermission {
	public static final String DEFAULT_DIALOG_PERMISSION = "emote.dialog.open";
	public static final String DEFAULT_PLAY_PERMISSION = "emote.play";
	public static final String DEFAULT_STOP_PERMISSION = "emote.stop";
	public static final String DEFAULT_COMMAND_LIST_PERMISSION = "emote.command.list";
	public static final String DEFAULT_COMMAND_RELOAD_PERMISSION = "emote.command.reload";
	public static final String DEFAULT_EMOTE_PERMISSION = "emote.play.{namespace}.{animation}";

	private EmotePermission() {
	}

	public static String createPlayPermission(String namespace, String animationName) {
		return "emote.play." + sanitize(namespace) + "." + sanitize(animationName);
	}

	public static String resolveEmotePermission(String permissionFormat, String namespace, String animationName) {
		if (permissionFormat == null || permissionFormat.isBlank()) {
			return "";
		}

		return permissionFormat
			.replace("{namespace}", sanitize(namespace))
			.replace("{animation}", sanitize(animationName));
	}

	private static String sanitize(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
