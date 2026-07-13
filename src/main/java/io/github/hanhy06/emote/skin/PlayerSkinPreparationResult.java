package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record PlayerSkinPreparationResult(PreparedPlayerSkin preparedSkin, String errorMessage) {
    public static PlayerSkinPreparationResult ready(PreparedPlayerSkin preparedSkin) {
        return new PlayerSkinPreparationResult(preparedSkin, null);
    }

    public static PlayerSkinPreparationResult failure(String errorMessage) {
        String normalizedMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
        if (normalizedMessage.isEmpty()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return new PlayerSkinPreparationResult(null, normalizedMessage);
    }

    public boolean isReady() {
        return this.errorMessage == null;
    }
}
