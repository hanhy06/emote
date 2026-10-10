package io.github.hanhy06.emote.content.loader;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.content.LoadedSequence;
import io.github.hanhy06.emote.content.LoadedAnimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import io.github.hanhy06.emote.api.EmoteLoadException;

public final class EmoteDirectoryLoader {
    private final AnimationJsonParser animationParser;
    private final SequenceJsonParser sequenceParser;

    public EmoteDirectoryLoader() {
        this(new AnimationJsonParser(), new SequenceJsonParser());
    }

    EmoteDirectoryLoader(
        AnimationJsonParser animationParser,
        SequenceJsonParser sequenceParser
    ) {
        this.animationParser = animationParser;
        this.sequenceParser = sequenceParser;
    }

    public LoadResult load(Path directory) {
        List<LoadedAnimation> candidates = new ArrayList<>();
        List<LoadedSequence> sequenceCandidates = new ArrayList<>();
        List<Path> detectedFiles = findJsonFiles(directory);
        for (Path path : detectedFiles) {
            try {
                EmoteJsonDocument document = EmoteJsonDocument.read(path);
                switch (document.type()) {
                    case "animation" -> candidates.add(this.animationParser.parse(document));
                    case "sequence" -> sequenceCandidates.add(this.sequenceParser.parse(document));
                    default -> throw document.error(
                        "$.type",
                        "unsupported emote file type: " + document.type()
                    );
                }
            } catch (EmoteLoadException exception) {
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
            throw new UncheckedIOException(exception);
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.comparing(path -> directory.relativize(path).toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to scan emote animation directory {}", directory, exception);
            throw new UncheckedIOException(exception);
        }
    }

    private LoadResult rejectDuplicateIds(
        List<LoadedAnimation> candidates,
        List<LoadedSequence> sequenceCandidates,
        int detectedFileCount
    ) {
        Map<String, List<Path>> pathsById = new LinkedHashMap<>();
        for (LoadedAnimation candidate : candidates) {
            pathsById.computeIfAbsent(candidate.model().id().toString(), ignored -> new ArrayList<>())
                .add(candidate.sourcePath());
        }
        for (LoadedSequence candidate : sequenceCandidates) {
            pathsById.computeIfAbsent(candidate.id().toString(), ignored -> new ArrayList<>()).add(candidate.sourcePath());
        }

        Set<String> duplicateIds = pathsById.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .peek(entry -> EmoteMod.LOGGER.warn("Ignoring emote files with duplicate ID {}: {}", entry.getKey(), entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<LoadedAnimation> loaded = candidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.model().id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.model().id().toString()))
            .toList();
        List<LoadedSequence> sequences = sequenceCandidates.stream()
            .filter(candidate -> !duplicateIds.contains(candidate.id().toString()))
            .sorted(Comparator.comparing(candidate -> candidate.id().toString()))
            .toList();
        return new LoadResult(loaded, sequences, detectedFileCount);
    }

    public record LoadResult(List<LoadedAnimation> animations, List<LoadedSequence> sequences, int detectedFileCount) {
        public LoadResult {
            animations = List.copyOf(animations);
            sequences = List.copyOf(sequences);
            if (detectedFileCount < animations.size() + sequences.size()) {
                throw new IllegalArgumentException("Detected file count must include every loaded file");
            }
        }
    }

}
