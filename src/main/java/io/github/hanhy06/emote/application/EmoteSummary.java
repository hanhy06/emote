package io.github.hanhy06.emote.application;

import java.util.List;
import java.util.Objects;

public record EmoteSummary(String id, String name, String description, List<String> tags) {
    public EmoteSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        tags = List.copyOf(tags);
    }

    public String createPlayCommand() {
        return "emote play " + this.id;
    }
}
