package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record PlayableEmote(String commandName, String displayName, String description) {
    public PlayableEmote {
        Objects.requireNonNull(commandName, "commandName");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
    }

    public String createPlayCommand() {
        return "emote play " + this.commandName;
    }

    public String selectionKey() {
        return this.commandName;
    }
}
