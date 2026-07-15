package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.JsonEmoteSkinPart;
import io.github.hanhy06.emote.skin.JsonEmoteSkinPartFactory;

import java.util.List;
import java.util.Objects;

public record EmoteDefinition(
    EmoteAnimation.Loaded loadedAnimation,
    List<JsonEmoteSkinPart> skinParts
) {
    public EmoteDefinition {
        Objects.requireNonNull(loadedAnimation, "loadedAnimation");
        skinParts = List.copyOf(skinParts);
    }

    public static EmoteDefinition create(EmoteAnimation.Loaded loadedAnimation) {
        return new EmoteDefinition(
            loadedAnimation,
            new JsonEmoteSkinPartFactory().create(loadedAnimation.animation())
        );
    }

    public String id() {
        return this.loadedAnimation.animation().id().toString();
    }

    public String name() {
        return this.loadedAnimation.animation().metadata().name();
    }

    public String description() {
        return this.loadedAnimation.animation().metadata().description();
    }

    public boolean hidePlayer() {
        return this.loadedAnimation.animation().metadata().hidePlayer();
    }

    public EmoteAnimation animation() {
        return this.loadedAnimation.animation();
    }

    public int partCount() {
        return this.animation().nodes().size();
    }
}
