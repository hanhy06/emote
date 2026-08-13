package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.playback.PlaybackPlan;
import io.github.hanhy06.emote.skin.AnimationSkinPart;
import io.github.hanhy06.emote.skin.AnimationSkinPartFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record RegisteredEmote(
    EmoteAnimation.Loaded source,
    List<AnimationSkinPart> skinParts,
    PlaybackPlan playbackPlan
) implements EmoteDefinition {
    private static final AnimationSkinPartFactory SKIN_PART_FACTORY = new AnimationSkinPartFactory();

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

    @Override
    public boolean standalone() {
        return animation().settings().standalone();
    }

    public EmotePlayerBehavior playerBehavior() {
        return animation().settings().player();
    }

    public Path sourcePath() {
        return this.source.sourcePath();
    }

    public int nodeCount() {
        return animation().nodes().size();
    }

    @Override
    public int durationTicks() {
        return animation().timeline().durationTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return animation().settings().playback().mode();
    }

    public int displayNodeCount() {
        return (int) animation().nodes().values().stream()
            .filter(node -> !(node instanceof EmoteAnimation.AnchorNode))
            .count();
    }
}
