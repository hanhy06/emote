package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteCallback;
import io.github.hanhy06.emote.api.EmoteInfo;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.SkinBinding;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public sealed interface PreparedEmote permits PreparedAnimation, PreparedSequence {
    String id();

    EmoteMetadata metadata();

    default EmoteInfo info() {
        return new EmoteInfo(
            Identifier.parse(id()),
            this instanceof PreparedSequence ? EmoteInfo.Kind.SEQUENCE : EmoteInfo.Kind.ANIMATION,
            standalone(),
            metadata(),
            playerBehavior(),
            durationTicks(),
            cooldownTicks(),
            playbackMode()
        );
    }

    default String name() {
        return metadata().name();
    }

    default String description() {
        return metadata().description();
    }

    default List<String> tags() {
        return metadata().tags();
    }

    boolean standalone();

    EmotePlayerBehavior playerBehavior();

    Path sourcePath();

    @org.jspecify.annotations.Nullable Integer durationTicks();

    int cooldownTicks();

    EmoteAnimation.PlaybackMode playbackMode();

    Map<String, EmoteAnimation.Node> nodes();
    List<String> nodeOrder();
    Map<String, Map<String, DisplayData>> displayContents();
    List<SkinBinding> skinBindings();

    default List<PreparedAnimation> animations() {
        if (this instanceof PreparedAnimation animation) return List.of(animation);
        return ((PreparedSequence) this).steps().stream().filter(PreparedSequence.AnimationStep.class::isInstance)
            .map(PreparedSequence.AnimationStep.class::cast).flatMap(step -> step.choices().stream())
            .filter(PreparedSequence.AnimationChoice.class::isInstance).map(PreparedSequence.AnimationChoice.class::cast)
            .map(PreparedSequence.AnimationChoice::animation).distinct().toList();
    }
    List<EmoteCallback> callbacks();
    int displayEntityCount();
    float rotationDeadzone();
    Matrix4fc defaultMatrix(String nodeId);

}
