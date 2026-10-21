package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;
import java.util.Objects;

public record LoadedAnimation(Path sourcePath, EmoteAnimation model) {
    public LoadedAnimation {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(model, "model");
    }
}
