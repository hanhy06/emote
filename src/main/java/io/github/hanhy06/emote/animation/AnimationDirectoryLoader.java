package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.content.LoadedAnimation;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

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

    public DirectoryContents load(Path directory) {
        return load(directory, this.serverValidator::prepare);
    }

    DirectoryContents load(Path directory, LoadedValidator validator) {
        List<LoadedAnimation> candidates = new ArrayList<>();
        List<EmoteSequence> sequenceCandidates = new ArrayList<>();
        List<Path> detectedFiles = findJsonFiles(directory);
        for (Path path : detectedFiles) {
            try {
                if (fileType(path).equals("sequence")) {
                    sequenceCandidates.add(this.sequenceJsonLoader.load(path));
                } else {
                    LoadedAnimation loaded = this.jsonLoader.load(path);
                    candidates.add(validator.validate(loaded));
                }
            } catch (EmoteAnimationLoadException exception) {
                Emote.LOGGER.warn("Ignoring invalid emote file: {}", exception.getMessage());
            }
        }
        return rejectDuplicateIds(candidates, sequenceCandidates, detectedFiles.size());
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

    private DirectoryContents rejectDuplicateIds(
        List<LoadedAnimation> candidates,
        List<EmoteSequence> sequenceCandidates,
        int detectedFileCount
    ) {
        Map<String, List<Path>> pathsById = new LinkedHashMap<>();
        for (LoadedAnimation candidate : candidates) {
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
        List<LoadedAnimation> loaded = candidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.animation().id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.animation().id().toString()))
            .toList();
        List<EmoteSequence> sequences = sequenceCandidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.id().toString()))
            .toList();
        return new DirectoryContents(loaded, sequences, detectedFileCount);
    }

    public record DirectoryContents(List<LoadedAnimation> animations, List<EmoteSequence> sequences, int detectedFileCount) {
        public DirectoryContents {
            animations = List.copyOf(animations);
            sequences = List.copyOf(sequences);
            if (detectedFileCount < animations.size() + sequences.size()) {
                throw new IllegalArgumentException("Detected file count must include every loaded file");
            }
        }
    }

    @FunctionalInterface
    interface LoadedValidator {
        LoadedAnimation validate(LoadedAnimation loaded) throws EmoteAnimationLoadException;
    }
}
