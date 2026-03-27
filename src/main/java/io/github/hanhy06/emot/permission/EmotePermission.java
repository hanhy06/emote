package io.github.hanhy06.emot.permission;

import java.util.Locale;

public final class EmotePermission {
	public static final String DIALOG_OPEN = "emote.dialog.open";
	public static final String PLAY = "emote.play";
	public static final String STOP = "emote.stop";
	public static final String COMMAND_LIST = "emote.command.list";
	public static final String COMMAND_RELOAD = "emote.command.reload";

	private EmotePermission() {
	}

	public static String createPlayPermission(String namespace, String animationName) {
		return PLAY + "." + sanitize(namespace) + "." + sanitize(animationName);
	}

	private static String sanitize(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
