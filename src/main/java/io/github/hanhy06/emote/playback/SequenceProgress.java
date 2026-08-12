package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredSequence;

import java.util.Objects;

final class SequenceProgress {
    private final RegisteredSequence sequence;
    private final RootTransform root;

    private int stepIndex;
    private int completedRepeats;

    SequenceProgress(RegisteredSequence sequence, RootTransform root) {
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.root = Objects.requireNonNull(root, "root");
    }

    RegisteredSequence sequence() {
        return this.sequence;
    }

    RootTransform root() {
        return this.root;
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
