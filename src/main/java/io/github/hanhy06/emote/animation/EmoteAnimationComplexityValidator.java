package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.nio.file.Path;

final class EmoteAnimationComplexityValidator {
    static final int MAX_DURATION_TICKS = 20 * 60 * 10;

    void validate(EmoteAnimation.Loaded loaded) throws EmoteAnimationLoadException {
        EmoteAnimation.Timeline timeline = loaded.animation().timeline();
        if (timeline.durationTicks() > MAX_DURATION_TICKS) {
            throw error(
                loaded.sourcePath(),
                "$.timeline.duration_ticks",
                "must not exceed " + MAX_DURATION_TICKS
            );
        }
    }

    private EmoteAnimationLoadException error(Path sourcePath, String fieldPath, String message) {
        return new EmoteAnimationLoadException(sourcePath, fieldPath, message);
    }
}
