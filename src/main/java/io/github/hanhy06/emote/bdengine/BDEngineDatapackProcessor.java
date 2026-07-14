package io.github.hanhy06.emote.bdengine;

import com.google.gson.Gson;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;
import net.minecraft.resources.Identifier;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class BDEngineDatapackProcessor {
    private static final String CREATE_FUNCTION_NAME = "create.mcfunction";
    private static final String EMOTE_METADATA_FILE_NAME = "emote.json";
    private static final Gson GSON = new Gson();
    private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern ENTRYPOINT_PATTERN = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern PLAYER_SKIN_MARKER_PATTERN = Pattern.compile("name\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORDERED_SKIN_MARKER_PATTERN = Pattern.compile("^emote:([a-z_]+)(?::(\\d+))?$");
    private static final Pattern TRANSFORMATION_PATTERN = Pattern.compile("transformation:\\[(.*?)]");
    private static final double ANCHOR_DISTANCE_EPSILON = 1.0E-9D;
    private final ConfigManager configManager;
    private final EmoteRegistry emoteRegistry;
    private final BDEngineDatapackScanner datapackScanner = new BDEngineDatapackScanner();

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
        List<EmoteDefinition> definitions = filterLoadedDefinitions(readDefinitions(datapackDirPath, this.configManager.getPackConfig()));
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

    private List<EmoteDefinition> filterLoadedDefinitions(List<EmoteDefinition> definitions) {
        return definitions.stream()
            .filter(definition -> !isMissingCreateFunction(definition))
            .filter(definition -> !isMissingFunction(definition.namespace() + ":" + definition.entrypoint()))
            .toList();
    }

    private boolean isMissingCreateFunction(EmoteDefinition definition) {
        return isMissingFunction(definition.namespace() + ":_/create");
    }

    private boolean isMissingFunction(String functionId) {
        MinecraftServer server = server();
        if (server == null) {
            return true;
        }

        Identifier identifier = Identifier.tryParse(functionId);
        return identifier == null || server.getFunctions().get(identifier).isEmpty();
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

        Path dataPath = packRootPath.resolve("data");
        if (!Files.isDirectory(dataPath)) {
            return false;
        }

        try (Stream<Path> namespacePathStream = Files.list(dataPath)) {
            for (Path namespacePath : namespacePathStream.filter(Files::isDirectory).toList()) {
                String namespace = namespacePath.getFileName().toString();
                if (packConfig.isEnabled(namespace)
                    && Files.isRegularFile(namespacePath.resolve(EMOTE_METADATA_FILE_NAME))
                    && isEmoteNamespace(namespacePath)) {
                    return true;
                }
            }
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to scan datapack namespaces from {}", packRootPath, exception);
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

        Path dataPath = packRootPath.resolve("data");
        if (!Files.isDirectory(dataPath)) {
            return List.of();
        }

        List<EmoteDefinition> definitions = new ArrayList<>();

        try (Stream<Path> namespacePathStream = Files.list(dataPath)) {
            for (Path namespacePath : namespacePathStream.filter(Files::isDirectory).sorted(pathComparator()).toList()) {
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
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to read datapack namespaces from {}", packPath, exception);
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
        CreateFunctionData createFunctionData = readCreateFunctionData(createFunctionPath, namespace);
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

    private CreateFunctionData readCreateFunctionData(Path createFunctionPath, String namespace) {
        try {
            String createFunction = Files.readString(createFunctionPath);
            Matcher itemDisplayMatcher = createItemDisplayPattern(namespace).matcher(createFunction);
            List<RawSkinPart> rawSkinParts = new ArrayList<>();
            int partCount = 0;

            while (itemDisplayMatcher.find()) {
                partCount++;

                String itemDisplayData = itemDisplayMatcher.group();
                String itemData = itemDisplayMatcher.group(1);
                int partIndex = Integer.parseInt(itemDisplayMatcher.group(2));
                RawSkinPart rawSkinPart = readSkinPart(itemDisplayData, itemData, partIndex);
                if (rawSkinPart != null) {
                    rawSkinParts.add(rawSkinPart);
                }
            }

            return new CreateFunctionData(partCount, assignSkinSegments(rawSkinParts));
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to read parts from {}", createFunctionPath, exception);
            return new CreateFunctionData(0, List.of());
        }
    }

    private Pattern createItemDisplayPattern(String namespace) {
        String pattern = "\\{id:\"minecraft:item_display\",item:\\{(.*?)},.*?Tags:\\[[^]]*?\"" + Pattern.quote(namespace) + "_(\\d+)\"[^]]*?]}";
        return Pattern.compile(pattern, Pattern.DOTALL);
    }

    private RawSkinPart readSkinPart(String itemDisplayData, String itemData, int partIndex) {
        if (!itemData.contains("id:\"minecraft:player_head\"")) {
            return null;
        }

        Matcher markerMatcher = PLAYER_SKIN_MARKER_PATTERN.matcher(itemData);
        if (!markerMatcher.find()) {
            return null;
        }

        double[] transformationValues = readTransformationValues(itemDisplayData);
        Matcher orderedMarkerMatcher = ORDERED_SKIN_MARKER_PATTERN.matcher(markerMatcher.group(1));
        if (!orderedMarkerMatcher.matches()) {
            return null;
        }
        PlayerSkinPart playerSkinPart = PlayerSkinPart.fromId(orderedMarkerMatcher.group(1));
        if (playerSkinPart == null) {
            return null;
        }
        Integer explicitOrder = orderedMarkerMatcher.group(2) == null
            ? null
            : Integer.parseInt(orderedMarkerMatcher.group(2));

        return new RawSkinPart(
            partIndex,
            playerSkinPart,
            readAnchorX(transformationValues),
            readAnchorY(transformationValues),
            readAnchorZ(transformationValues),
            readLocalY(transformationValues),
            readLocalYScale(transformationValues),
            explicitOrder
        );
    }

    private List<EmoteSkinPart> assignSkinSegments(List<RawSkinPart> rawSkinParts) {
        if (rawSkinParts.isEmpty()) {
            return List.of();
        }

        Map<PlayerSkinPart, List<RawSkinPart>> rawSkinPartMap = new java.util.EnumMap<>(PlayerSkinPart.class);
        for (RawSkinPart rawSkinPart : rawSkinParts) {
            rawSkinPartMap.computeIfAbsent(rawSkinPart.skinPart(), ignored -> new ArrayList<>()).add(rawSkinPart);
        }

        double[] limbRoot = averageAnchor(rawSkinPartMap.get(PlayerSkinPart.BODY));
        if (limbRoot == null) {
            limbRoot = averageAnchor(rawSkinPartMap.get(PlayerSkinPart.HEAD));
        }

        List<EmoteSkinPart> skinParts = new ArrayList<>();
        for (Map.Entry<PlayerSkinPart, List<RawSkinPart>> entry : rawSkinPartMap.entrySet()) {
            PlayerSkinPart skinPart = entry.getKey();
            List<RawSkinPart> partsForSkin = new ArrayList<>(entry.getValue());
            boolean hasExplicitOrder = isLimb(skinPart)
                && partsForSkin.stream().allMatch(part -> part.explicitOrder() != null);
            if (hasExplicitOrder) {
                partsForSkin.sort(
                    Comparator.comparingInt((RawSkinPart part) -> part.explicitOrder())
                        .thenComparingInt(RawSkinPart::partIndex)
                );
            } else if (isLimb(skinPart) && limbRoot != null) {
                partsForSkin = orderConnectedParts(partsForSkin, limbRoot);
            } else {
                partsForSkin.sort(
                    Comparator.comparingInt(RawSkinPart::partIndex)
                        .thenComparing(Comparator.comparingDouble(RawSkinPart::localY).reversed())
                );
            }

            skinParts.addAll(createSkinParts(skinPart, partsForSkin));
        }

        skinParts.sort(Comparator.comparingInt(EmoteSkinPart::partIndex));
        return List.copyOf(skinParts);
    }

    private boolean isLimb(PlayerSkinPart skinPart) {
        return skinPart == PlayerSkinPart.LEFT_ARM
            || skinPart == PlayerSkinPart.RIGHT_ARM
            || skinPart == PlayerSkinPart.LEFT_LEG
            || skinPart == PlayerSkinPart.RIGHT_LEG;
    }

    private double[] averageAnchor(List<RawSkinPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }

        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (RawSkinPart part : parts) {
            x += part.anchorX();
            y += part.anchorY();
            z += part.anchorZ();
        }
        return new double[]{x / parts.size(), y / parts.size(), z / parts.size()};
    }

    private List<RawSkinPart> orderConnectedParts(List<RawSkinPart> parts, double[] limbRoot) {
        List<RawSkinPart> remainingParts = new ArrayList<>(parts);
        List<RawSkinPart> orderedParts = new ArrayList<>(parts.size());
        double[] previousAnchor = limbRoot;
        while (!remainingParts.isEmpty()) {
            RawSkinPart nextPart = remainingParts.getFirst();
            double nextDistance = anchorDistanceSquared(nextPart, previousAnchor);
            for (RawSkinPart candidate : remainingParts.subList(1, remainingParts.size())) {
                double candidateDistance = anchorDistanceSquared(candidate, previousAnchor);
                if (candidateDistance < nextDistance - ANCHOR_DISTANCE_EPSILON
                    || (Math.abs(candidateDistance - nextDistance) <= ANCHOR_DISTANCE_EPSILON
                    && candidate.partIndex() < nextPart.partIndex())) {
                    nextPart = candidate;
                    nextDistance = candidateDistance;
                }
            }
            orderedParts.add(nextPart);
            remainingParts.remove(nextPart);
            previousAnchor = new double[]{nextPart.anchorX(), nextPart.anchorY(), nextPart.anchorZ()};
        }
        return orderedParts;
    }

    private double anchorDistanceSquared(RawSkinPart part, double[] anchor) {
        double offsetX = part.anchorX() - anchor[0];
        double offsetY = part.anchorY() - anchor[1];
        double offsetZ = part.anchorZ() - anchor[2];
        return offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
    }

    private List<EmoteSkinPart> createSkinParts(PlayerSkinPart skinPart, List<RawSkinPart> partsForSkin) {
        if (partsForSkin.isEmpty()) {
            return List.of();
        }

        if (skinPart == PlayerSkinPart.HEAD || partsForSkin.size() == 1) {
            List<EmoteSkinPart> fullSkinParts = new ArrayList<>(partsForSkin.size());
            for (RawSkinPart rawSkinPart : partsForSkin) {
                fullSkinParts.add(new EmoteSkinPart(rawSkinPart.partIndex(), rawSkinPart.skinPart(), PlayerSkinSegment.FULL));
            }
            return fullSkinParts;
        }

        if (partsForSkin.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            Emote.LOGGER.warn("Too many vertical skin segments for {}: {}", skinPart.id(), partsForSkin.size());
            List<EmoteSkinPart> fallbackSkinParts = new ArrayList<>(partsForSkin.size());
            for (RawSkinPart rawSkinPart : partsForSkin) {
                fallbackSkinParts.add(new EmoteSkinPart(rawSkinPart.partIndex(), rawSkinPart.skinPart(), PlayerSkinSegment.FULL));
            }
            return fallbackSkinParts;
        }

        double totalScale = partsForSkin.stream()
            .mapToDouble(rawSkinPart -> Math.max(rawSkinPart.localYScale(), 0.0D))
            .sum();
        if (totalScale <= 0.0D) {
            totalScale = partsForSkin.size();
        }

        List<EmoteSkinPart> segmentedSkinParts = new ArrayList<>(partsForSkin.size());
        int segmentStart = 0;
        double accumulatedScale = 0.0D;
        for (int index = 0; index < partsForSkin.size(); index++) {
            RawSkinPart rawSkinPart = partsForSkin.get(index);
            double partScale = Math.max(rawSkinPart.localYScale(), 0.0D);
            if (partScale <= 0.0D) {
                partScale = 1.0D;
            }

            accumulatedScale += partScale;
            int remainingPartCount = partsForSkin.size() - index - 1;
            int segmentEnd = calculateSegmentEnd(segmentStart, accumulatedScale, totalScale, remainingPartCount);
            segmentedSkinParts.add(new EmoteSkinPart(
                rawSkinPart.partIndex(),
                rawSkinPart.skinPart(),
                new PlayerSkinSegment(segmentStart, segmentEnd)
            ));
            segmentStart = segmentEnd;
        }

        return segmentedSkinParts;
    }

    private int calculateSegmentEnd(int segmentStart, double accumulatedScale, double totalScale, int remainingPartCount) {
        int minEnd = segmentStart + 1;
        int maxEnd = Math.max(minEnd, PlayerSkinSegment.SIDE_FACE_HEIGHT - remainingPartCount);
        int suggestedEnd = (int) Math.round(accumulatedScale * PlayerSkinSegment.SIDE_FACE_HEIGHT / totalScale);
        if (suggestedEnd < minEnd) {
            return minEnd;
        }

        return Math.min(suggestedEnd, maxEnd);
    }

    private double[] readTransformationValues(String itemDisplayData) {
        Matcher transformationMatcher = TRANSFORMATION_PATTERN.matcher(itemDisplayData);
        if (!transformationMatcher.find()) {
            return null;
        }

        String[] values = transformationMatcher.group(1).split(",");
        if (values.length < 16) {
            return null;
        }

        double[] transformationValues = new double[16];
        try {
            for (int index = 0; index < transformationValues.length; index++) {
                transformationValues[index] = parseMatrixNumber(values[index]);
            }
            return transformationValues;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double readLocalY(double[] transformationValues) {
        if (transformationValues == null) {
            return 0.0D;
        }

        return transformationValues[7];
    }

    private double readAnchorX(double[] transformationValues) {
        if (transformationValues == null) {
            return 0.0D;
        }
        return transformationValues[3] - transformationValues[1] * 0.25D;
    }

    private double readAnchorY(double[] transformationValues) {
        if (transformationValues == null) {
            return 0.0D;
        }
        return transformationValues[7] - transformationValues[5] * 0.25D;
    }

    private double readAnchorZ(double[] transformationValues) {
        if (transformationValues == null) {
            return 0.0D;
        }
        return transformationValues[11] - transformationValues[9] * 0.25D;
    }

    private double readLocalYScale(double[] transformationValues) {
        if (transformationValues == null) {
            return 1.0D;
        }

        return readLocalYAxisScale(transformationValues);
    }

    private double readLocalYAxisScale(double[] transformationValues) {
        double firstValue = transformationValues[1];
        double secondValue = transformationValues[5];
        double thirdValue = transformationValues[9];
        return Math.sqrt(firstValue * firstValue + secondValue * secondValue + thirdValue * thirdValue);
    }

    private double parseMatrixNumber(String value) {
        String normalizedValue = value.trim();
        if (normalizedValue.endsWith("f") || normalizedValue.endsWith("d")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }

        return Double.parseDouble(normalizedValue);
    }

    private Comparator<Path> pathComparator() {
        return Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
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

    private record CreateFunctionData(int partCount, List<EmoteSkinPart> skinParts) {
    }

    private record RawSkinPart(
        int partIndex,
        PlayerSkinPart skinPart,
        double anchorX,
        double anchorY,
        double anchorZ,
        double localY,
        double localYScale,
        Integer explicitOrder
    ) {
    }
}
