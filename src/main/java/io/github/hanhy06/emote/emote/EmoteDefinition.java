package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;

public sealed interface EmoteDefinition permits RegisteredEmote, RegisteredSequence {
    String id();

    String name();

    String description();

    boolean standalone();

    EmotePlayerBehavior playerBehavior();

    Path sourcePath();

    int durationTicks();

    EmoteAnimation.LoopMode loopMode();

    int nodeCount();
}
