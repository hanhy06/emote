package io.github.hanhy06.emote.sequence;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record EmoteSequence(
    Path sourcePath,
    Identifier id,
    Metadata metadata,
    EmoteAnimation.PlayerBehavior player,
    List<Step> steps
) {
    public EmoteSequence {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(player, "player");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence steps must not be empty");
        }
    }

    public record Metadata(String name, String description) {
        public Metadata {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
        }
    }

    public record Step(List<Choice> choices, int repeat) {
        public Step {
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

        public Step(Identifier emoteId, int repeat) {
            this(List.of(new Choice(Objects.requireNonNull(emoteId, "emoteId"), 0)), repeat);
        }

        public Step(java.util.Collection<Identifier> emoteIds, int repeat) {
            this(emoteIds.stream().map(emoteId -> new Choice(emoteId, 0)).toList(), repeat);
        }

        public List<Identifier> emoteIds() {
            return this.choices.stream().map(Choice::emoteId).toList();
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
