package io.github.hanhy06.emote.api;

import java.util.Objects;

public record EmotePlayerBehavior(boolean hidden, StopConditions stopConditions) {
    public EmotePlayerBehavior {
        Objects.requireNonNull(stopConditions, "stopConditions");
    }

    public static EmotePlayerBehavior createDefault() {
        return new EmotePlayerBehavior(true, StopConditions.createDefault());
    }

    public record StopConditions(
        double movementDistance,
        boolean jump,
        boolean submerge,
        boolean ride,
        boolean damage,
        boolean attack,
        boolean gameModeChange
    ) {
        public StopConditions {
            if (!Double.isFinite(movementDistance) || movementDistance < 0.0D) {
                throw new IllegalArgumentException("movement distance must be a finite non-negative number");
            }
        }

        public static StopConditions createDefault() {
            return new StopConditions(0.1D, true, true, true, true, true, true);
        }
    }
}
