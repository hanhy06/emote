package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.content.LoadedAnimation;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.CompiledTimeline;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.SkinBindingCompiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record PreparedEmote(
    LoadedAnimation source,
    List<SkinBinding> skinParts,
    CompiledTimeline compiledTimeline
) implements PreparedDefinition {
    private static final SkinBindingCompiler SKIN_PART_FACTORY = new SkinBindingCompiler();

    public PreparedEmote {
        Objects.requireNonNull(source, "source");
        skinParts = List.copyOf(skinParts);
        Objects.requireNonNull(compiledTimeline, "compiledTimeline");
    }

    public static PreparedEmote from(LoadedAnimation source) {
        return new PreparedEmote(
            source,
            SKIN_PART_FACTORY.create(source.animation()),
            CompiledTimeline.compile(source.animation())
        );
    }

    public EmoteAnimation animation() {
        return this.source.animation();
    }

    public String id() {
        return animation().id().toString();
    }

    public EmoteMetadata metadata() {
        return animation().metadata();
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
    public int cooldownTicks() {
        return animation().settings().cooldownTicks();
    }

    @Override
    public EmoteAnimation.LoopMode loopMode() {
        return animation().settings().playback().mode();
    }

    public int displayNodeCount() {
        return this.compiledTimeline.displayNodeCount();
    }

    public List<SkinBinding> skinParts(ParticipantRole participant) {
        return this.skinParts.stream()
            .filter(binding -> binding.participant() == participant)
            .toList();
    }
}
