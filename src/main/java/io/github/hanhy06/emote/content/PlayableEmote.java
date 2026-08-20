package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;

public sealed interface PlayableEmote permits PreparedAnimation, PreparedSequence {
    String id();

    EmoteMetadata metadata();

    default String name() {
        return metadata().name();
    }

    default String description() {
        return metadata().description();
    }

    boolean standalone();

    EmotePlayerBehavior playerBehavior();

    Path sourcePath();

    int durationTicks();

    int cooldownTicks();

    EmoteAnimation.LoopMode loopMode();

    int nodeCount();
}
