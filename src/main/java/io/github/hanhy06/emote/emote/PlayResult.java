package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayResult(String errorMessage) {
    public static final PlayResult SUCCESS = new PlayResult(null);

    public PlayResult {
        if (errorMessage != null) {
            errorMessage = errorMessage.trim();
            if (errorMessage.isEmpty()) {
                throw new IllegalArgumentException("errorMessage must not be blank");
            }
        }
    }

    public static PlayResult failure(String errorMessage) {
        return new PlayResult(Objects.requireNonNull(errorMessage, "errorMessage"));
    }

    public boolean isSuccess() {
        return this.errorMessage == null;
    }
}
