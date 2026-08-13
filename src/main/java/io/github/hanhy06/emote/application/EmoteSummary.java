package io.github.hanhy06.emote.application;

import java.util.Objects;

public record EmoteSummary(String id, String displayName, String description) {
    public EmoteSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
    }

    public String createPlayCommand() {
        return "emote play " + this.id;
    }
}
