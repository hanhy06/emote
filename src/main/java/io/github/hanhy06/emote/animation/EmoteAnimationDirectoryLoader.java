package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.Emote;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static io.github.hanhy06.emote.animation.EmoteAnimation.Loaded;

public final class EmoteAnimationDirectoryLoader {
    private final EmoteAnimationJsonLoader jsonLoader;
    private final EmoteAnimationServerValidator serverValidator;

    public EmoteAnimationDirectoryLoader() {
        this(new EmoteAnimationJsonLoader(), new EmoteAnimationServerValidator());
    }

    EmoteAnimationDirectoryLoader(
        EmoteAnimationJsonLoader jsonLoader,
        EmoteAnimationServerValidator serverValidator
    ) {
        this.jsonLoader = jsonLoader;
        this.serverValidator = serverValidator;
    }

    public List<Loaded> load(Path directory, MinecraftServer server) {
        return load(directory, server.getServerVersion(), loaded -> this.serverValidator.prepare(loaded, server));
    }

    List<Loaded> load(Path directory, String minecraftVersion, LoadedValidator validator) {
        List<Loaded> candidates = new ArrayList<>();
        for (Path path : findJsonFiles(directory)) {
            try {
                Loaded loaded = this.jsonLoader.load(path, minecraftVersion);
                candidates.add(validator.validate(loaded));
            } catch (EmoteAnimationLoadException exception) {
                Emote.LOGGER.warn("Ignoring invalid emote animation: {}", exception.getMessage());
            }
        }
        return rejectDuplicateIds(candidates);
    }

    private List<Path> findJsonFiles(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create emote animation directory {}", directory, exception);
            return List.of();
        }

        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to scan emote animation directory {}", directory, exception);
            return List.of();
        }
    }

    private List<Loaded> rejectDuplicateIds(List<Loaded> candidates) {
        Map<String, List<Loaded>> byId = new LinkedHashMap<>();
        for (Loaded candidate : candidates) {
            byId.computeIfAbsent(candidate.animation().id().toString(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<Loaded> loaded = new ArrayList<>();
        for (Map.Entry<String, List<Loaded>> entry : byId.entrySet()) {
            if (entry.getValue().size() == 1) {
                loaded.add(entry.getValue().getFirst());
                continue;
            }
            Emote.LOGGER.warn(
                "Ignoring emote animations with duplicate id {}: {}",
                entry.getKey(),
                entry.getValue().stream().map(Loaded::sourcePath).toList()
            );
        }
        loaded.sort(Comparator.comparing(candidate -> candidate.animation().id().toString()));
        return List.copyOf(loaded);
    }

    @FunctionalInterface
    interface LoadedValidator {
        Loaded validate(Loaded loaded) throws EmoteAnimationLoadException;
    }
}
