package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.SkinBinding;
import org.joml.Matrix4fc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import io.github.hanhy06.emote.api.EmoteCallback;

public sealed interface PreparedEmote permits PreparedAnimation, PreparedSequence {
    String id();

    EmoteMetadata metadata();

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

    default int nodeCount() { return nodes().size(); }

    Map<String, EmoteAnimation.Node> nodes();
    List<String> nodeOrder();
    Map<String, Map<String, DisplayData>> displayContents();
    List<SkinBinding> skinBindings();
    List<EmoteCallback> callbacks();
    int displayEntityCount();
    float rotationDeadzone();
    Matrix4fc defaultMatrix(String nodeId);

}
