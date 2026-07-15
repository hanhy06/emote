package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.JsonEmoteSkinPart;
import io.github.hanhy06.emote.skin.JsonEmoteSkinPartFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record RegisteredEmote(
    EmoteAnimation.Loaded source,
    List<JsonEmoteSkinPart> skinParts
) {
    public RegisteredEmote {
        Objects.requireNonNull(source, "source");
        skinParts = List.copyOf(skinParts);
    }

    public static RegisteredEmote from(EmoteAnimation.Loaded source) {
        return new RegisteredEmote(source, new JsonEmoteSkinPartFactory().create(source.animation()));
    }

    public EmoteAnimation animation() {
        return this.source.animation();
    }

    public String id() {
        return animation().id().toString();
    }

    public String name() {
        return animation().metadata().name();
    }

    public String description() {
        return animation().metadata().description();
    }

    public boolean hidePlayer() {
        return animation().metadata().hidePlayer();
    }

    public Path sourcePath() {
        return this.source.sourcePath();
    }

    public String fingerprint() {
        return this.source.sha256();
    }

    public int nodeCount() {
        return animation().nodes().size();
    }
}
