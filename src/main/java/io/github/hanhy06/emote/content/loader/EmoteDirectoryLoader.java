package io.github.hanhy06.emote.content.loader;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteSequence;
import io.github.hanhy06.emote.content.LoadedAnimation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class EmoteDirectoryLoader {
    private final AnimationJsonParser animationParser;
    private final SequenceJsonParser sequenceParser;
    private final AnimationContentResolver contentResolver;

    public EmoteDirectoryLoader() {
        this(new AnimationJsonParser(), new SequenceJsonParser(), new AnimationContentResolver());
    }

    EmoteDirectoryLoader(
        AnimationJsonParser animationParser,
        SequenceJsonParser sequenceParser,
        AnimationContentResolver contentResolver
    ) {
        this.animationParser = animationParser;
        this.sequenceParser = sequenceParser;
        this.contentResolver = contentResolver;
    }

    public LoadResult load(Path directory) {
        return load(directory, this.contentResolver::resolve);
    }

    LoadResult load(Path directory, AnimationResolver resolver) {
        List<LoadedAnimation> candidates = new ArrayList<>();
        List<EmoteSequence> sequenceCandidates = new ArrayList<>();
        List<Path> detectedFiles = findJsonFiles(directory);
        for (Path path : detectedFiles) {
            try {
                EmoteJsonDocument document = EmoteJsonDocument.read(path);
                switch (document.type()) {
                    case "animation" -> candidates.add(resolver.resolve(this.animationParser.parse(document)));
                    case "sequence" -> sequenceCandidates.add(this.sequenceParser.parse(document));
                    default -> throw document.reader().error(
                        "$.type",
                        "unsupported emote file type: " + document.type()
                    );
                }
            } catch (EmoteAnimationLoadException exception) {
                EmoteMod.LOGGER.warn("Ignoring invalid emote file: {}", exception.getMessage());
            }
        }
        return rejectDuplicateIds(candidates, sequenceCandidates, detectedFiles.size());
    }

    private List<Path> findJsonFiles(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to create emote animation directory {}", directory, exception);
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.comparing(path -> directory.relativize(path).toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to scan emote animation directory {}", directory, exception);
            return List.of();
        }
    }

    private LoadResult rejectDuplicateIds(
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
            .peek(entry -> EmoteMod.LOGGER.warn("Ignoring emote files with duplicate ID {}: {}", entry.getKey(), entry.getValue()))
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
        return new LoadResult(loaded, sequences, detectedFileCount);
    }

    public record LoadResult(List<LoadedAnimation> animations, List<EmoteSequence> sequences, int detectedFileCount) {
        public LoadResult {
            animations = List.copyOf(animations);
            sequences = List.copyOf(sequences);
            if (detectedFileCount < animations.size() + sequences.size()) {
                throw new IllegalArgumentException("Detected file count must include every loaded file");
            }
        }
    }

    @FunctionalInterface
    interface AnimationResolver {
        LoadedAnimation resolve(LoadedAnimation loaded) throws EmoteAnimationLoadException;
    }
}
