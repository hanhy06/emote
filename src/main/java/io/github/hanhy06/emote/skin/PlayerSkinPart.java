package io.github.hanhy06.emote.skin;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum PlayerSkinPart {
    HEAD("head"),
    BODY("body"),
    RIGHT_ARM("right_arm"),
    LEFT_ARM("left_arm"),
    RIGHT_LEG("right_leg"),
    LEFT_LEG("left_leg");

    private static final Map<String, PlayerSkinPart> BY_ID = java.util.Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(PlayerSkinPart::id, Function.identity()));
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
        if (normalizedId.startsWith("emote:")) {
            normalizedId = normalizedId.substring("emote:".length());
        }
        return BY_ID.get(normalizedId);
    }
}
