package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.Objects;

public record LoadedSequence(Path sourcePath, EmoteSequence sequence) {
    public LoadedSequence {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(sequence, "sequence");
    }

    public Identifier id() {
        return this.sequence.id();
    }
}
