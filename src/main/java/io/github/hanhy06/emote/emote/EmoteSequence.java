package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public record EmoteSequence(
    Path sourcePath,
    Identifier id,
    EmoteMetadata metadata,
    Settings settings,
    List<Step> steps
) {
    public EmoteSequence {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(settings, "settings");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence steps must not be empty");
        }
        if (steps.getFirst() instanceof WaitStep || steps.getLast() instanceof WaitStep) {
            throw new IllegalArgumentException("sequence wait steps must be between emote steps");
        }
        for (int index = 1; index < steps.size(); index++) {
            if (steps.get(index - 1) instanceof WaitStep && steps.get(index) instanceof WaitStep) {
                throw new IllegalArgumentException("sequence wait steps must not be consecutive");
            }
        }
    }

    public record Settings(int cooldownTicks, EmotePlayerBehavior player) {
        public Settings {
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("cooldown must not be negative");
            }
            Objects.requireNonNull(player, "player");
        }
    }

    public sealed interface Step permits EmoteStep, WaitStep {
    }

    public record EmoteStep(List<Choice> choices, int repeat) implements Step {
        public EmoteStep {
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("sequence emote candidates must not be empty");
            }
            if (choices.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("choices");
            }
            if (choices.stream().map(Choice::emoteId).distinct().count() != choices.size()) {
                throw new IllegalArgumentException("sequence emote candidates must not contain duplicates");
            }
            boolean weighted = choices.getFirst().chance() > 0;
            if (choices.stream().anyMatch(choice -> (choice.chance() > 0) != weighted)) {
                throw new IllegalArgumentException("sequence emote candidates must use either equal or explicit chances");
            }
            if (weighted && choices.stream().mapToInt(Choice::chance).sum() != 100) {
                throw new IllegalArgumentException("sequence emote candidate chances must total 100");
            }
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
        }

        public EmoteStep(Identifier emoteId, int repeat) {
            this(List.of(new Choice(Objects.requireNonNull(emoteId, "emoteId"), 0)), repeat);
        }

        public EmoteStep(Collection<Identifier> emoteIds, int repeat) {
            this(emoteIds.stream().map(emoteId -> new Choice(emoteId, 0)).toList(), repeat);
        }

        public List<Identifier> emoteIds() {
            return this.choices.stream().map(Choice::emoteId).toList();
        }
    }

    public record WaitStep(int ticks) implements Step {
        public WaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

    public record Choice(Identifier emoteId, int chance) {
        public Choice {
            Objects.requireNonNull(emoteId, "emoteId");
            if (chance < 0 || chance > 100) {
                throw new IllegalArgumentException("sequence emote candidate chance must be between 1 and 100");
            }
        }
    }
}
