package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredSequence;

import java.util.Objects;

final class SequenceProgress {
    private final RegisteredSequence sequence;

    private int stepIndex;
    private int completedRepeats;

    SequenceProgress(RegisteredSequence sequence) {
        this.sequence = Objects.requireNonNull(sequence, "sequence");
    }

    RegisteredSequence sequence() {
        return this.sequence;
    }

    RegisteredEmote currentAnimation() {
        return currentStep().animation();
    }

    boolean completeCycle() {
        this.completedRepeats++;
        if (this.completedRepeats < currentStep().repeat()) {
            return false;
        }
        this.completedRepeats = 0;
        this.stepIndex++;
        return this.stepIndex >= this.sequence.steps().size();
    }

    private RegisteredSequence.Step currentStep() {
        return this.sequence.steps().get(this.stepIndex);
    }
}
