package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.EmoteSkinPartFactory;
import io.github.hanhy06.emote.playback.PlaybackPlan;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record RegisteredEmote(
    EmoteAnimation.Loaded source,
    List<EmoteSkinPart> skinParts,
    PlaybackPlan playbackPlan
) {
    private static final EmoteSkinPartFactory SKIN_PART_FACTORY = new EmoteSkinPartFactory();

    public RegisteredEmote {
        Objects.requireNonNull(source, "source");
        skinParts = List.copyOf(skinParts);
        Objects.requireNonNull(playbackPlan, "playbackPlan");
    }

    public static RegisteredEmote from(EmoteAnimation.Loaded source) {
        return new RegisteredEmote(
            source,
            SKIN_PART_FACTORY.create(source.animation()),
            PlaybackPlan.compile(source.animation())
        );
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

    public int nodeCount() {
        return animation().nodes().size();
    }
}
