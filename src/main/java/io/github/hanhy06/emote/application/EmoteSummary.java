package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.util.EmoteTags;

import java.util.List;
import java.util.Objects;

public record EmoteSummary(String id, String displayName, String description, List<String> tags) {
    public EmoteSummary(String id, String displayName, String description) {
        this(id, displayName, description, EmoteTags.extract(description));
    }

    public EmoteSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        tags = List.copyOf(tags);
    }

    public String createPlayCommand() {
        return "emote play " + this.id;
    }
}
