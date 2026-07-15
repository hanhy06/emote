package io.github.hanhy06.emote.animation;

import java.nio.file.Path;
import java.util.Objects;

public class EmoteAnimationLoadException extends Exception {
    private final Path sourcePath;
    private final String fieldPath;

    public EmoteAnimationLoadException(Path sourcePath, String fieldPath, String message) {
        super(message);
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.fieldPath = Objects.requireNonNull(fieldPath, "fieldPath");
    }

    public EmoteAnimationLoadException(Path sourcePath, String fieldPath, String message, Throwable cause) {
        super(message, cause);
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.fieldPath = Objects.requireNonNull(fieldPath, "fieldPath");
    }

    public String fieldPath() {
        return this.fieldPath;
    }

    @Override
    public String getMessage() {
        return this.sourcePath + " at " + this.fieldPath + ": " + super.getMessage();
    }
}
