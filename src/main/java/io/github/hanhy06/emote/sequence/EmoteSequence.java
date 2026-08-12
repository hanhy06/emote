package io.github.hanhy06.emote.sequence;

import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record EmoteSequence(
    Path sourcePath,
    Identifier id,
    Metadata metadata,
    List<Step> steps
) {
    public EmoteSequence {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
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

    public record Step(Identifier emoteId, int repeat) {
        public Step {
            Objects.requireNonNull(emoteId, "emoteId");
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
        }
    }
}
