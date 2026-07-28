package io.github.hanhy06.emote.api;

import net.minecraft.network.chat.Component;

import java.util.Objects;

public record PlayResult(Component errorMessage) {
    public static final PlayResult SUCCESS = new PlayResult(null);

    public static PlayResult failure(String errorMessage) {
        String normalizedMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
        if (normalizedMessage.isEmpty()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return failure(Component.literal(normalizedMessage));
    }

    public static PlayResult failure(Component errorMessage) {
        return new PlayResult(Objects.requireNonNull(errorMessage, "errorMessage"));
    }

    public boolean isSuccess() {
        return this.errorMessage == null;
    }
}
