package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.sequence.EmoteSequence;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.Loaded;

public final class AnimationDirectoryLoader {
    private final AnimationJsonLoader jsonLoader;
    private final SequenceJsonLoader sequenceJsonLoader;
    private final AnimationServerPreparer serverValidator;

    public AnimationDirectoryLoader() {
        this(new AnimationJsonLoader(), new SequenceJsonLoader(), new AnimationServerPreparer());
    }

    AnimationDirectoryLoader(
        AnimationJsonLoader jsonLoader,
        SequenceJsonLoader sequenceJsonLoader,
        AnimationServerPreparer serverValidator
    ) {
        this.jsonLoader = jsonLoader;
        this.sequenceJsonLoader = sequenceJsonLoader;
        this.serverValidator = serverValidator;
    }

    AnimationDirectoryLoader(AnimationJsonLoader jsonLoader, AnimationServerPreparer serverValidator) {
        this(jsonLoader, new SequenceJsonLoader(), serverValidator);
    }

    public List<Loaded> load(Path directory) {
        return load(directory, Emote.SERVER.getServerVersion(), this.serverValidator::prepare);
    }

    List<Loaded> load(Path directory, String minecraftVersion, LoadedValidator validator) {
        return loadAll(directory, minecraftVersion, validator).animations();
    }

    public DirectoryContents loadAll(Path directory) {
        return loadAll(directory, Emote.SERVER.getServerVersion(), this.serverValidator::prepare);
    }

    DirectoryContents loadAll(Path directory, String minecraftVersion, LoadedValidator validator) {
        List<Loaded> candidates = new ArrayList<>();
        List<EmoteSequence> sequenceCandidates = new ArrayList<>();
        for (Path path : findJsonFiles(directory)) {
            try {
                if (fileType(path).equals("sequence")) {
                    sequenceCandidates.add(this.sequenceJsonLoader.load(path));
                } else {
                    Loaded loaded = this.jsonLoader.load(path, minecraftVersion);
                    candidates.add(validator.validate(loaded));
                }
            } catch (EmoteAnimationLoadException exception) {
                Emote.LOGGER.warn("Ignoring invalid emote file: {}", exception.getMessage());
            }
        }
        return rejectDuplicateIds(candidates, sequenceCandidates);
    }

    private String fileType(Path path) throws EmoteAnimationLoadException {
        try {
            if (Files.size(path) > AnimationJsonLoader.MAX_JSON_BYTES) {
                throw new EmoteAnimationLoadException(
                    path,
                    "$",
                    "file must not exceed " + AnimationJsonLoader.MAX_JSON_BYTES + " bytes"
                );
            }
            JsonElement element = JsonParser.parseString(Files.readString(path));
            if (!element.isJsonObject()) {
                return "animation";
            }
            JsonElement type = element.getAsJsonObject().get("type");
            if (type == null || type.isJsonNull()) {
                return "animation";
            }
            if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
                throw new EmoteAnimationLoadException(path, "$.type", "must be a string");
            }
            String value = type.getAsString();
            if (!value.equals("animation") && !value.equals("sequence")) {
                throw new EmoteAnimationLoadException(path, "$.type", "unsupported emote file type: " + value);
            }
            return value;
        } catch (IOException | com.google.gson.JsonParseException exception) {
            throw new EmoteAnimationLoadException(path, "$", "failed to detect emote file type", exception);
        }
    }

    private List<Path> findJsonFiles(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create emote animation directory {}", directory, exception);
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.comparing(path -> directory.relativize(path).toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to scan emote animation directory {}", directory, exception);
            return List.of();
        }
    }

    private DirectoryContents rejectDuplicateIds(List<Loaded> candidates, List<EmoteSequence> sequenceCandidates) {
        Map<String, List<Path>> pathsById = new LinkedHashMap<>();
        for (Loaded candidate : candidates) {
            pathsById.computeIfAbsent(candidate.animation().id().toString(), ignored -> new ArrayList<>())
                .add(candidate.sourcePath());
        }
        for (EmoteSequence candidate : sequenceCandidates) {
            pathsById.computeIfAbsent(candidate.id().toString(), ignored -> new ArrayList<>()).add(candidate.sourcePath());
        }

        Set<String> duplicateIds = pathsById.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .peek(entry -> Emote.LOGGER.warn("Ignoring emote files with duplicate id {}: {}", entry.getKey(), entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Loaded> loaded = candidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.animation().id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.animation().id().toString()))
            .toList();
        List<EmoteSequence> sequences = sequenceCandidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.id().toString()))
            .toList();
        return new DirectoryContents(loaded, sequences);
    }

    public record DirectoryContents(List<Loaded> animations, List<EmoteSequence> sequences) {
        public DirectoryContents {
            animations = List.copyOf(animations);
            sequences = List.copyOf(sequences);
        }
    }

    @FunctionalInterface
    interface LoadedValidator {
        Loaded validate(Loaded loaded) throws EmoteAnimationLoadException;
    }
}
