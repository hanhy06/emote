package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record LoadedAnimation(
    Path sourcePath,
    String sha256,
    EmoteAnimation animation,
    Map<String, PreparedDisplayData> preparedDisplayData
) {
    public LoadedAnimation(Path sourcePath, String sha256, EmoteAnimation animation) {
        this(sourcePath, sha256, animation, Map.of());
    }

    public LoadedAnimation {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(animation, "animation");
        preparedDisplayData = Map.copyOf(preparedDisplayData);
    }
}
