package io.github.hanhy06.emote.skin;

import java.util.Locale;

public enum PlayerSkinPart {
	HEAD("head"),
	BODY("body"),
	RIGHT_ARM("right_arm"),
	LEFT_ARM("left_arm"),
	RIGHT_LEG("right_leg"),
	LEFT_LEG("left_leg");

	private final String id;

	PlayerSkinPart(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public static PlayerSkinPart fromId(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}

		String normalizedId = id.toLowerCase(Locale.ROOT);
		return switch (normalizedId) {
			case "head" -> HEAD;
			case "body" -> BODY;
			case "right_arm" -> RIGHT_ARM;
			case "left_arm" -> LEFT_ARM;
			case "right_leg" -> RIGHT_LEG;
			case "left_leg" -> LEFT_LEG;
			case "emote:head" -> HEAD;
			case "emote:body" -> BODY;
			case "emote:right_arm" -> RIGHT_ARM;
			case "emote:left_arm" -> LEFT_ARM;
			case "emote:right_leg" -> RIGHT_LEG;
			case "emote:left_leg" -> LEFT_LEG;
			default -> null;
		};
	}
}
