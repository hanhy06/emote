package io.github.hanhy06.emote.skin;

import java.util.Locale;
import java.util.Optional;

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

	public static Optional<PlayerSkinPart> fromId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}

		String normalizedId = id.toLowerCase(Locale.ROOT);
		return switch (normalizedId) {
			case "emote:head" -> Optional.of(HEAD);
			case "emote:body" -> Optional.of(BODY);
			case "emote:right_arm" -> Optional.of(RIGHT_ARM);
			case "emote:left_arm" -> Optional.of(LEFT_ARM);
			case "emote:right_leg" -> Optional.of(RIGHT_LEG);
			case "emote:left_leg" -> Optional.of(LEFT_LEG);
			default -> Optional.empty();
		};
	}
}
