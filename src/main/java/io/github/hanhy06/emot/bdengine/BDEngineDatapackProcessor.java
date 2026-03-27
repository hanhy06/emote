package io.github.hanhy06.emot.bdengine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emot.Emote;
import io.github.hanhy06.emot.emote.EmoteAnimation;
import io.github.hanhy06.emot.emote.EmoteDefinition;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import io.github.hanhy06.emot.skin.EmoteSkinPart;
import io.github.hanhy06.emot.skin.PlayerSkinPart;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class BDEngineDatapackProcessor {
	private static final String CREATE_FUNCTION_NAME = "create.mcfunction";
	private static final String PLAY_FUNCTION_NAME = "play_anim.mcfunction";
	private static final String DATAPACK_META_FILE_NAME = "emote-datapack.json";
	private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("[a-z0-9_-]+");
	private static final Pattern PLAYER_SKIN_MARKER_PATTERN = Pattern.compile("name\\s*:\\s*\"emote:skin:([a-z_]+)\"");
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

		LinkedHashSet<String> selectedPackIds = new LinkedHashSet<>(server.getPackRepository().getSelectedIds());
		Collection<String> availablePackIds = server.getPackRepository().getAvailableIds();
		boolean changed = false;

		for (String packId : findEmotePackIds(server.getWorldPath(LevelResource.DATAPACK_DIR))) {
			if (!availablePackIds.contains(packId)) {
				continue;
			}

			if (selectedPackIds.add(packId)) {
				changed = true;
			}
		}

		if (changed) {
			server.reloadResources(selectedPackIds).join();
		}

		return changed;
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

		Optional<EmoteDatapackMeta> datapackMeta = readDatapackMeta(packPath, packRootPath);
		if (datapackMeta.isEmpty()) {
			return List.of();
		}

		Path dataPath = packRootPath.resolve("data");
		if (!Files.isDirectory(dataPath)) {
			return List.of();
		}

		List<EmoteDefinition> definitions = new ArrayList<>();

		try (Stream<Path> namespacePathStream = Files.list(dataPath)) {
			for (Path namespacePath : namespacePathStream.filter(Files::isDirectory).sorted(pathComparator()).toList()) {
				readDefinition(packPath, namespacePath, datapackMeta.get()).ifPresent(definitions::add);
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read datapack namespaces from {}", packPath, exception);
		}

		return List.copyOf(definitions);
	}

	private Optional<EmoteDefinition> readDefinition(Path packPath, Path namespacePath, EmoteDatapackMeta datapackMeta) {
		Path functionPath = findFunctionPath(namespacePath);
		if (functionPath == null) {
			return Optional.empty();
		}

		Path createFunctionPath = functionPath.resolve("_").resolve(CREATE_FUNCTION_NAME);
		if (!Files.exists(createFunctionPath)) {
			return Optional.empty();
		}

		String namespace = namespacePath.getFileName().toString();
		List<EmoteAnimation> animations = readAnimations(functionPath);
		CreateFunctionData createFunctionData = readCreateFunctionData(createFunctionPath, namespace);
		return Optional.of(new EmoteDefinition(
			namespace,
			datapackMeta.name(),
			datapackMeta.description(),
			createCommandName(packPath, namespace, datapackMeta.commandName()),
			createDefaultAnimationName(datapackMeta.defaultAnimationName()),
			packPath,
			createFunctionData.partCount(),
			animations,
			createFunctionData.skinParts()
		));
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
			List<EmoteSkinPart> skinParts = new ArrayList<>();
			int partCount = 0;

			while (itemDisplayMatcher.find()) {
				partCount++;

				String itemData = itemDisplayMatcher.group(1);
				int partIndex = Integer.parseInt(itemDisplayMatcher.group(2));
				readSkinPart(itemData, partIndex).ifPresent(skinParts::add);
			}

			return new CreateFunctionData(partCount, List.copyOf(skinParts));
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read parts from {}", createFunctionPath, exception);
			return new CreateFunctionData(0, List.of());
		}
	}

	private Pattern createItemDisplayPattern(String namespace) {
		String pattern = "\\{id:\"minecraft:item_display\",item:\\{(.*?)\\},.*?Tags:\\[[^\\]]*?\"" + Pattern.quote(namespace) + "_(\\d+)\"[^\\]]*?\\]\\}";
		return Pattern.compile(pattern, Pattern.DOTALL);
	}

	private Optional<EmoteSkinPart> readSkinPart(String itemData, int partIndex) {
		if (!itemData.contains("id:\"minecraft:player_head\"")) {
			return Optional.empty();
		}

		Matcher markerMatcher = PLAYER_SKIN_MARKER_PATTERN.matcher(itemData);
		if (!markerMatcher.find()) {
			return Optional.empty();
		}

		return PlayerSkinPart.fromId(markerMatcher.group(1))
			.map(playerSkinPart -> new EmoteSkinPart(partIndex, playerSkinPart));
	}

	private Comparator<Path> pathComparator() {
		return Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
	}

	private Optional<EmoteDatapackMeta> readDatapackMeta(Path packPath, Path packRootPath) {
		Path datapackMetaPath = packRootPath.resolve(DATAPACK_META_FILE_NAME);
		if (!Files.exists(datapackMetaPath)) {
			return Optional.empty();
		}

		try (Reader reader = Files.newBufferedReader(datapackMetaPath)) {
			JsonElement element = JsonParser.parseReader(reader);
			if (!element.isJsonObject()) {
				Emote.LOGGER.warn("Skip {}: {} must be a JSON object.", packPath.getFileName(), DATAPACK_META_FILE_NAME);
				return Optional.empty();
			}

			JsonObject object = element.getAsJsonObject();
			String name = readRequiredString(object, "name");
			String description = readRequiredString(object, "description");
			if (name == null || description == null) {
				Emote.LOGGER.warn("Skip {}: {} needs name and description.", packPath.getFileName(), DATAPACK_META_FILE_NAME);
				return Optional.empty();
			}

			return Optional.of(new EmoteDatapackMeta(
				name,
				description,
				readOptionalString(object, "command_name"),
				readOptionalString(object, "default_animation")
			));
		} catch (IOException | RuntimeException exception) {
			Emote.LOGGER.warn("Skip {}: failed to read {}.", packPath.getFileName(), DATAPACK_META_FILE_NAME, exception);
			return Optional.empty();
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
}
