package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

final class AnimationComplexityValidator {
    static final int MAX_DURATION_TICKS = 20 * 60 * 10;

    void validate(EmoteAnimation.Loaded loaded) throws EmoteAnimationLoadException {
        EmoteAnimation.Timeline timeline = loaded.animation().timeline();
        if (timeline.durationTicks() > MAX_DURATION_TICKS) {
            throw new EmoteAnimationLoadException(
                loaded.sourcePath(),
                "$.timeline.duration_ticks",
                "must not exceed " + MAX_DURATION_TICKS
            );
        }
    }
}
