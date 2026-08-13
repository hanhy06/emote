package io.github.hanhy06.emote.sequence;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.HashSet;
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

    public record Step(List<Identifier> emoteIds, int repeat) {
        public Step {
            emoteIds = List.copyOf(emoteIds);
            if (emoteIds.isEmpty()) {
                throw new IllegalArgumentException("sequence emote candidates must not be empty");
            }
            if (emoteIds.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("emoteIds");
            }
            if (new HashSet<>(emoteIds).size() != emoteIds.size()) {
                throw new IllegalArgumentException("sequence emote candidates must not contain duplicates");
            }
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
        }

        public Step(Identifier emoteId, int repeat) {
            this(List.of(Objects.requireNonNull(emoteId, "emoteId")), repeat);
        }
    }
}
