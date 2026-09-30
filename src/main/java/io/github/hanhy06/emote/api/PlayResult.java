package io.github.hanhy06.emote.api;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public sealed interface PlayResult {
    record Success(PlaybackHandle handle) implements PlayResult {
        public Success {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record Failure(Component errorMessage) implements PlayResult {
        public Failure {
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    static PlayResult failure(String errorMessage) {
        String normalizedMessage = Objects.requireNonNull(errorMessage, "errorMessage").trim();
        if (normalizedMessage.isEmpty()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return failure(Component.literal(normalizedMessage));
    }

    static PlayResult failure(Component errorMessage) {
        return new Failure(errorMessage);
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default @Nullable Component errorMessage() {
        return this instanceof Failure failure ? failure.errorMessage() : null;
    }
}
