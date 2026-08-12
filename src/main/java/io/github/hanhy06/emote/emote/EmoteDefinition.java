package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;

public sealed interface EmoteDefinition permits RegisteredEmote, RegisteredSequence {
    String id();

    String name();

    String description();

    Path sourcePath();

    int durationTicks();

    EmoteAnimation.LoopMode loopMode();

    int nodeCount();
}
