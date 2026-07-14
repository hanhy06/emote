package io.github.hanhy06.emote.bdengine;

import com.google.gson.Gson;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.emote.EmoteDatapackNames;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.server.ServerFunctionLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class BDEngineDatapackProcessor {
    private static final String CREATE_FUNCTION_NAME = "create.mcfunction";
    private static final String EMOTE_METADATA_FILE_NAME = "emote.json";
    private static final Gson GSON = new Gson();
    private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern ENTRYPOINT_PATTERN = Pattern.compile("[a-z0-9_./-]+");
    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final BDEngineDatapackScanner datapackScanner = new BDEngineDatapackScanner();
    private final BDEngineCreateFunctionParser createFunctionParser = new BDEngineCreateFunctionParser();

    public BDEngineDatapackProcessor(ConfigManager configManager, EmoteRegistry emoteRegistry) {
        this.configManager = configManager;
        this.emoteRegistry = emoteRegistry;
    }

    public int reloadServerEmotes() {
        MinecraftServer server = server();
        if (server == null) {
            return 0;
        }

        Path datapackDirPath = server.getWorldPath(LevelResource.DATAPACK_DIR);
        List<EmoteDefinition> definitions = filterLoadedDefinitions(
            server,
            readDefinitions(datapackDirPath, this.configManager.getPackConfig())
        );
        this.emoteRegistry.replaceDefinitions(definitions);
        return definitions.size();
    }

    public boolean enableEmoteDatapacks() {
        MinecraftServer server = server();
        if (server == null) {
            return false;
        }

        server.getPackRepository().reload();

        List<String> selectedPackIds = List.copyOf(server.getPackRepository().getSelectedIds());
        Collection<String> availablePackIds = server.getPackRepository().getAvailableIds();
        LinkedHashSet<String> emotePackIds = new LinkedHashSet<>();

        for (String packId : findEmotePackIds(server.getWorldPath(LevelResource.DATAPACK_DIR), this.configManager.getPackConfig())) {
            if (!availablePackIds.contains(packId)) {
                continue;
            }

            emotePackIds.add(packId);
        }

        List<String> reorderedPackIds = new ArrayList<>();
        for (String selectedPackId : selectedPackIds) {
            if (!emotePackIds.contains(selectedPackId)) {
                reorderedPackIds.add(selectedPackId);
            }
        }

        reorderedPackIds.addAll(emotePackIds);

        if (!selectedPackIds.equals(reorderedPackIds)) {
            server.reloadResources(reorderedPackIds).join();
            return true;
        }

        return false;
    }

    private List<EmoteDefinition> filterLoadedDefinitions(
        MinecraftServer server,
        List<EmoteDefinition> definitions
    ) {
        return definitions.stream()
            .filter(definition -> ServerFunctionLookup.isLoaded(
                server,
                EmoteDatapackNames.createFunctionId(definition.namespace())
            ))
            .filter(definition -> ServerFunctionLookup.isLoaded(
                server,
                EmoteDatapackNames.entrypointFunctionId(definition.namespace(), definition.entrypoint())
            ))
            .toList();
    }

    private MinecraftServer server() {
        return Emote.SERVER;
    }

    List<String> findEmotePackIds(Path datapackDirPath, PackConfig packConfig) {
        return this.datapackScanner.scan(datapackDirPath, (packPath, packRootPath) ->
            hasEnabledEmoteNamespace(packRootPath, packConfig)
                ? List.of("file/" + packPath.getFileName())
                : List.of()
        );
    }

    private boolean hasEnabledEmoteNamespace(Path packRootPath, PackConfig packConfig) {
        if (!Files.exists(packRootPath.resolve("pack.mcmeta"))) {
            return false;
        }

        for (Path namespacePath : this.datapackScanner.findNamespacePaths(packRootPath)) {
            String namespace = namespacePath.getFileName().toString();
            if (packConfig.isEnabled(namespace)
                && Files.isRegularFile(namespacePath.resolve(EMOTE_METADATA_FILE_NAME))
                && isEmoteNamespace(namespacePath)) {
                return true;
            }
        }

        return false;
    }

    List<EmoteDefinition> readDefinitions(Path datapackDirPath, PackConfig packConfig) {
        return filterDefinitionConflicts(this.datapackScanner.scan(
            datapackDirPath,
            (packPath, packRootPath) -> readPackRoot(packPath, packRootPath, packConfig)
        ));
    }

    private List<EmoteDefinition> filterDefinitionConflicts(List<EmoteDefinition> definitions) {
        Map<String, EmoteDefinition> namespaceDefinitions = new LinkedHashMap<>();
        Set<String> conflictingNamespaces = new HashSet<>();
        for (EmoteDefinition definition : definitions) {
            String namespace = definition.namespace();
            if (namespaceDefinitions.putIfAbsent(namespace, definition) != null) {
                conflictingNamespaces.add(namespace);
            }
        }

        List<EmoteDefinition> namespaceCandidates = definitions.stream()
            .filter(definition -> !conflictingNamespaces.contains(definition.namespace()))
            .toList();
        Map<String, EmoteDefinition> commandDefinitions = new LinkedHashMap<>();
        Set<String> conflictingCommands = new HashSet<>();
        for (EmoteDefinition definition : namespaceCandidates) {
            String commandName = normalizeSelectionKey(definition.commandName());
            if (commandDefinitions.putIfAbsent(commandName, definition) != null) {
                conflictingCommands.add(commandName);
            }

            EmoteDefinition namespaceDefinition = namespaceDefinitions.get(commandName);
            if (namespaceDefinition != null && namespaceDefinition != definition) {
                conflictingCommands.add(commandName);
            }
        }

        for (String namespace : conflictingNamespaces) {
            Emote.LOGGER.warn("Ignoring emote definitions with duplicate namespace: {}", namespace);
        }
        for (String commandName : conflictingCommands) {
            Emote.LOGGER.warn("Ignoring emote definitions with conflicting command_name: {}", commandName);
        }

        return namespaceCandidates.stream()
            .filter(definition -> !conflictingCommands.contains(normalizeSelectionKey(definition.commandName())))
            .toList();
    }

    private String normalizeSelectionKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<EmoteDefinition> readPackRoot(Path packPath, Path packRootPath, PackConfig packConfig) {
        if (!Files.exists(packRootPath.resolve("pack.mcmeta"))) {
            return List.of();
        }

        List<EmoteDefinition> definitions = new ArrayList<>();
        for (Path namespacePath : this.datapackScanner.findNamespacePaths(packRootPath)) {
            if (!packConfig.isEnabled(namespacePath.getFileName().toString())) {
                continue;
            }

            if (!isEmoteNamespace(namespacePath)) {
                continue;
            }

            EmoteMetadata metadata = readMetadata(namespacePath.resolve(EMOTE_METADATA_FILE_NAME));
            if (metadata == null) {
                continue;
            }

            EmoteDefinition definition = readDefinition(packPath, namespacePath, metadata);
            if (definition != null) {
                definitions.add(definition);
            }
        }

        return List.copyOf(definitions);
    }

    private EmoteMetadata readMetadata(Path metadataPath) {
        if (!Files.isRegularFile(metadataPath)) {
            return null;
        }

        try {
            return GSON.fromJson(Files.readString(metadataPath), EmoteMetadata.class);
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read emote metadata from {}", metadataPath, exception);
            return null;
        }
    }

    private EmoteDefinition readDefinition(Path packPath, Path namespacePath, EmoteMetadata metadata) {
        Path functionPath = findFunctionPath(namespacePath);
        if (functionPath == null) {
            return null;
        }

        Path createFunctionPath = functionPath.resolve("_").resolve(CREATE_FUNCTION_NAME);
        if (!Files.exists(createFunctionPath)) {
            return null;
        }

        String namespace = namespacePath.getFileName().toString();
        String entrypoint = createEntrypoint(packPath, metadata.entrypoint());
        if (entrypoint == null || !Files.isRegularFile(functionPath.resolve(entrypoint + ".mcfunction"))) {
            return null;
        }
        BDEngineCreateFunctionParser.Result createFunctionData = this.createFunctionParser.parse(createFunctionPath, namespace);
        return new EmoteDefinition(
            namespace,
            metadata.name().trim(),
            metadata.description().trim(),
            createCommandName(packPath, namespace, metadata.command_name()),
            entrypoint,
            metadata.hide_player(),
            packPath,
            createFunctionData.partCount(),
            createFunctionData.skinParts()
        );
    }

    private Path findFunctionPath(Path namespacePath) {
        Path functionPath = namespacePath.resolve("function");
        if (Files.isDirectory(functionPath)) {
            return functionPath;
        }

        Path functionsPath = namespacePath.resolve("functions");
        if (Files.isDirectory(functionsPath)) {
            return functionsPath;
        }

        return null;
    }

    private boolean isEmoteNamespace(Path namespacePath) {
        Path functionPath = findFunctionPath(namespacePath);
        return functionPath != null && Files.isRegularFile(functionPath.resolve("_").resolve(CREATE_FUNCTION_NAME));
    }

    private String createCommandName(Path packPath, String namespace, String commandName) {
        String normalizedCommandName = commandName == null ? "" : commandName.trim().toLowerCase(Locale.ROOT);
        if (normalizedCommandName.isEmpty()) {
            return namespace;
        }

        if (!COMMAND_NAME_PATTERN.matcher(normalizedCommandName).matches()) {
            Emote.LOGGER.warn("Invalid command_name in {}. Using namespace.", packPath.getFileName());
            return namespace;
        }

        return normalizedCommandName;
    }

    private String createEntrypoint(Path packPath, String entrypoint) {
        String normalizedEntrypoint = entrypoint == null ? "" : entrypoint.trim().toLowerCase(Locale.ROOT);
        if (normalizedEntrypoint.endsWith(".mcfunction")) {
            normalizedEntrypoint = normalizedEntrypoint.substring(0, normalizedEntrypoint.length() - ".mcfunction".length());
        }
        if (normalizedEntrypoint.isEmpty()
            || normalizedEntrypoint.startsWith("/")
            || normalizedEntrypoint.contains("..")
            || !ENTRYPOINT_PATTERN.matcher(normalizedEntrypoint).matches()) {
            Emote.LOGGER.warn("Invalid entrypoint in {}", packPath.getFileName());
            return null;
        }
        return normalizedEntrypoint;
    }

}
