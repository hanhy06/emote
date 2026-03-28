package io.github.hanhy06.emote.bdengine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.emote.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class BDEngineDatapackProcessor {
	private static final String CREATE_FUNCTION_NAME = "create.mcfunction";
	private static final String PLAY_FUNCTION_NAME = "play_anim.mcfunction";
	private static final String DATAPACK_META_FILE_NAME = "emote-datapack.json";
	private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("[a-z0-9_-]+");
	private static final Pattern PLAYER_SKIN_MARKER_PATTERN = Pattern.compile("name\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern TRANSFORMATION_PATTERN = Pattern.compile("transformation:\\[(.*?)\\]");
	private final EmoteRegistry emoteRegistry;

	public BDEngineDatapackProcessor(EmoteRegistry emoteRegistry) {
		this.emoteRegistry = emoteRegistry;
	}

	public int reloadServerEmotes(MinecraftServer server) {
		Path datapackDirPath = server.getWorldPath(LevelResource.DATAPACK_DIR);
		List<EmoteDefinition> definitions = filterLoadedDefinitions(server, readDefinitions(datapackDirPath));
		this.emoteRegistry.replaceDefinitions(definitions);
		return definitions.size();
	}

	public boolean enableEmoteDatapacks(MinecraftServer server) {
		server.getPackRepository().reload();

		List<String> selectedPackIds = List.copyOf(server.getPackRepository().getSelectedIds());
		Collection<String> availablePackIds = server.getPackRepository().getAvailableIds();
		LinkedHashSet<String> emotePackIds = new LinkedHashSet<>();

		for (String packId : findEmotePackIds(server.getWorldPath(LevelResource.DATAPACK_DIR))) {
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

	private List<EmoteDefinition> filterLoadedDefinitions(MinecraftServer server, List<EmoteDefinition> definitions) {
		List<EmoteDefinition> filteredDefinitions = new ArrayList<>();

		for (EmoteDefinition definition : definitions) {
			if (!isLoadedFunction(server, definition.namespace() + ":_/create")) {
				continue;
			}

			List<EmoteAnimation> loadedAnimations = definition.animations().stream()
				.filter(animation -> isLoadedFunction(server, definition.namespace() + ":a/" + animation.name() + "/play_anim"))
				.toList();
			if (loadedAnimations.isEmpty()) {
				continue;
			}

			filteredDefinitions.add(new EmoteDefinition(
				definition.namespace(),
				definition.name(),
				definition.description(),
				definition.commandName(),
				definition.defaultAnimationName(),
				definition.datapackPath(),
				definition.partCount(),
				loadedAnimations,
				definition.skinParts()
			));
		}

		return List.copyOf(filteredDefinitions);
	}

	private boolean isLoadedFunction(MinecraftServer server, String functionId) {
		Identifier identifier = Identifier.tryParse(functionId);
		return identifier != null && server.getFunctions().get(identifier).isPresent();
	}

	private List<String> findEmotePackIds(Path datapackDirPath) {
		if (!Files.isDirectory(datapackDirPath)) {
			return List.of();
		}

		List<String> packIds = new ArrayList<>();
		try (Stream<Path> packPathStream = Files.list(datapackDirPath)) {
			for (Path packPath : packPathStream.sorted(pathComparator()).toList()) {
				if (isEmotePack(packPath)) {
					packIds.add("file/" + packPath.getFileName());
				}
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to scan datapack ids from {}", datapackDirPath, exception);
		}

		return List.copyOf(packIds);
	}

	private boolean isEmotePack(Path packPath) {
		if (Files.isDirectory(packPath)) {
			return hasDatapackFiles(packPath);
		}

		String fileName = packPath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!Files.isRegularFile(packPath) || !fileName.endsWith(".zip")) {
			return false;
		}

		try (FileSystem fileSystem = FileSystems.newFileSystem(packPath, Map.of())) {
			return hasDatapackFiles(fileSystem.getPath("/"));
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to inspect datapack {}", packPath, exception);
			return false;
		}
	}

	private boolean hasDatapackFiles(Path packRootPath) {
		return Files.exists(packRootPath.resolve("pack.mcmeta")) && Files.exists(packRootPath.resolve(DATAPACK_META_FILE_NAME));
	}

	private List<EmoteDefinition> readDefinitions(Path datapackDirPath) {
		if (!Files.isDirectory(datapackDirPath)) {
			return List.of();
		}

		List<EmoteDefinition> definitions = new ArrayList<>();

		try (Stream<Path> packPathStream = Files.list(datapackDirPath)) {
			for (Path packPath : packPathStream.sorted(pathComparator()).toList()) {
				definitions.addAll(readPackDefinitions(packPath));
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to scan datapack directory {}", datapackDirPath, exception);
		}

		return List.copyOf(definitions);
	}

	private List<EmoteDefinition> readPackDefinitions(Path packPath) {
		if (Files.isDirectory(packPath)) {
			return readPackRoot(packPath, packPath);
		}

		String fileName = packPath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!Files.isRegularFile(packPath) || !fileName.endsWith(".zip")) {
			return List.of();
		}

		try (FileSystem fileSystem = FileSystems.newFileSystem(packPath, Map.of())) {
			return readPackRoot(packPath, fileSystem.getPath("/"));
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read zipped datapack {}", packPath, exception);
			return List.of();
		}
	}

	private List<EmoteDefinition> readPackRoot(Path packPath, Path packRootPath) {
		if (!Files.exists(packRootPath.resolve("pack.mcmeta"))) {
			return List.of();
		}

		EmoteDatapackMeta datapackMeta = readDatapackMeta(packPath, packRootPath);
		if (datapackMeta == null) {
			return List.of();
		}

		Path dataPath = packRootPath.resolve("data");
		if (!Files.isDirectory(dataPath)) {
			return List.of();
		}

		List<EmoteDefinition> definitions = new ArrayList<>();

		try (Stream<Path> namespacePathStream = Files.list(dataPath)) {
			for (Path namespacePath : namespacePathStream.filter(Files::isDirectory).sorted(pathComparator()).toList()) {
				EmoteDefinition definition = readDefinition(packPath, namespacePath, datapackMeta);
				if (definition != null) {
					definitions.add(definition);
				}
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read datapack namespaces from {}", packPath, exception);
		}

		return List.copyOf(definitions);
	}

	private EmoteDefinition readDefinition(Path packPath, Path namespacePath, EmoteDatapackMeta datapackMeta) {
		Path functionPath = findFunctionPath(namespacePath);
		if (functionPath == null) {
			return null;
		}

		Path createFunctionPath = functionPath.resolve("_").resolve(CREATE_FUNCTION_NAME);
		if (!Files.exists(createFunctionPath)) {
			return null;
		}

		String namespace = namespacePath.getFileName().toString();
		List<EmoteAnimation> animations = readAnimations(functionPath);
		CreateFunctionData createFunctionData = readCreateFunctionData(createFunctionPath, namespace);
		return new EmoteDefinition(
			namespace,
			datapackMeta.name(),
			datapackMeta.description(),
			createCommandName(packPath, namespace, datapackMeta.commandName()),
			createDefaultAnimationName(datapackMeta.defaultAnimationName()),
			packPath,
			createFunctionData.partCount(),
			animations,
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

	private List<EmoteAnimation> readAnimations(Path functionPath) {
		Path animationPath = functionPath.resolve("a");
		if (!Files.isDirectory(animationPath)) {
			return List.of();
		}

		List<EmoteAnimation> animations = new ArrayList<>();
		Path keyframePath = functionPath.resolve("k");

		try (Stream<Path> animationPathStream = Files.list(animationPath)) {
			for (Path singleAnimationPath : animationPathStream.filter(Files::isDirectory).sorted(pathComparator()).toList()) {
				Path playFunctionPath = singleAnimationPath.resolve(PLAY_FUNCTION_NAME);
				if (!Files.exists(playFunctionPath)) {
					continue;
				}

				String animationName = singleAnimationPath.getFileName().toString();
				int keyframeCount = countKeyframes(keyframePath.resolve(animationName));
				animations.add(new EmoteAnimation(animationName, keyframeCount));
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read BD Engine animation paths from {}", functionPath, exception);
		}

		return List.copyOf(animations);
	}

	private int countKeyframes(Path keyframeAnimationPath) {
		if (!Files.isDirectory(keyframeAnimationPath)) {
			return 0;
		}

		try (Stream<Path> filePathStream = Files.list(keyframeAnimationPath)) {
			return (int) filePathStream
				.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(fileName -> fileName.startsWith("keyframe_") && fileName.endsWith(".mcfunction"))
				.count();
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to count keyframes from {}", keyframeAnimationPath, exception);
			return 0;
		}
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
		String pattern = "\\{id:\"minecraft:item_display\",item:\\{(.*?)\\},.*?Tags:\\[[^\\]]*?\"" + Pattern.quote(namespace) + "_(\\d+)\"[^\\]]*?\\]\\}";
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
		PlayerSkinPart playerSkinPart = PlayerSkinPart.fromId(markerMatcher.group(1));
		if (playerSkinPart == null) {
			return null;
		}

		return new RawSkinPart(
			partIndex,
			playerSkinPart,
			readLocalY(transformationValues),
			readLocalYScale(transformationValues)
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

		List<EmoteSkinPart> skinParts = new ArrayList<>();
		for (Map.Entry<PlayerSkinPart, List<RawSkinPart>> entry : rawSkinPartMap.entrySet()) {
			PlayerSkinPart skinPart = entry.getKey();
			List<RawSkinPart> partsForSkin = new ArrayList<>(entry.getValue());
			partsForSkin.sort(
				// BDEngine export order stays stable even if a limb is posed upward in create.mcfunction.
				Comparator.comparingInt(RawSkinPart::partIndex)
					.thenComparing(Comparator.comparingDouble(RawSkinPart::localY).reversed())
			);

			skinParts.addAll(createSkinParts(skinPart, partsForSkin));
		}

		skinParts.sort(Comparator.comparingInt(EmoteSkinPart::partIndex));
		return List.copyOf(skinParts);
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

	private double readLocalYScale(double[] transformationValues) {
		if (transformationValues == null) {
			return 1.0D;
		}

		return readAxisScale(transformationValues, 1, 5, 9);
	}

	private double readAxisScale(double[] transformationValues, int firstIndex, int secondIndex, int thirdIndex) {
		double firstValue = transformationValues[firstIndex];
		double secondValue = transformationValues[secondIndex];
		double thirdValue = transformationValues[thirdIndex];
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

	private EmoteDatapackMeta readDatapackMeta(Path packPath, Path packRootPath) {
		Path datapackMetaPath = packRootPath.resolve(DATAPACK_META_FILE_NAME);
		if (!Files.exists(datapackMetaPath)) {
			return null;
		}

		try (Reader reader = Files.newBufferedReader(datapackMetaPath)) {
			JsonElement element = JsonParser.parseReader(reader);
			if (!element.isJsonObject()) {
				Emote.LOGGER.warn("Skip {}: {} must be a JSON object.", packPath.getFileName(), DATAPACK_META_FILE_NAME);
				return null;
			}

			JsonObject object = element.getAsJsonObject();
			String name = readRequiredString(object, "name");
			String description = readRequiredString(object, "description");
			if (name == null || description == null) {
				Emote.LOGGER.warn("Skip {}: {} needs name and description.", packPath.getFileName(), DATAPACK_META_FILE_NAME);
				return null;
			}

			return new EmoteDatapackMeta(
				name,
				description,
				readOptionalString(object, "command_name"),
				readOptionalString(object, "default_animation")
			);
		} catch (IOException | RuntimeException exception) {
			Emote.LOGGER.warn("Skip {}: failed to read {}.", packPath.getFileName(), DATAPACK_META_FILE_NAME, exception);
			return null;
		}
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

	private String createDefaultAnimationName(String defaultAnimationName) {
		if (defaultAnimationName == null || defaultAnimationName.isBlank()) {
			return "default";
		}

		return defaultAnimationName.trim();
	}

	private String readRequiredString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}

		String value = element.getAsString().trim();
		return value.isEmpty() ? null : value;
	}

	private String readOptionalString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}

		String value = element.getAsString().trim();
		return value.isEmpty() ? null : value;
	}

	private record EmoteDatapackMeta(
		String name,
		String description,
		String commandName,
		String defaultAnimationName
	) {
	}

	private record CreateFunctionData(int partCount, List<EmoteSkinPart> skinParts) {
	}

	private record RawSkinPart(int partIndex, PlayerSkinPart skinPart, double localY, double localYScale) {
	}
}
