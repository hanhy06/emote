package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmote(String id, String displayName, String description) {
    public PlayableEmote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
    }

    public String createPlayCommand() {
        return "emote play " + this.id;
    }
}
