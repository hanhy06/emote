package io.github.hanhy06.emot.bdengine;

import io.github.hanhy06.emot.Emote;
import io.github.hanhy06.emot.emote.EmoteAnimation;
import io.github.hanhy06.emot.emote.EmoteDefinition;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class BDEngineDatapackProcessor {
	private static final String CREATE_FUNCTION_NAME = "_create.mcfunction";
	private static final String PLAY_FUNCTION_NAME = "play_anim.mcfunction";
	private final EmoteRegistry emoteRegistry;

	public BDEngineDatapackProcessor(EmoteRegistry emoteRegistry) {
		this.emoteRegistry = emoteRegistry;
	}

	public int reloadServerEmotes(MinecraftServer server) {
		Path datapackDirPath = server.getWorldPath(LevelResource.DATAPACK_DIR);
		List<EmoteDefinition> definitions = readDefinitions(datapackDirPath);
		this.emoteRegistry.replaceDefinitions(definitions);
		return definitions.size();
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

		Path dataPath = packRootPath.resolve("data");
		if (!Files.isDirectory(dataPath)) {
			return List.of();
		}

		List<EmoteDefinition> definitions = new ArrayList<>();

		try (Stream<Path> namespacePathStream = Files.list(dataPath)) {
			for (Path namespacePath : namespacePathStream.filter(Files::isDirectory).sorted(pathComparator()).toList()) {
				readDefinition(packPath, namespacePath).ifPresent(definitions::add);
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to read datapack namespaces from {}", packPath, exception);
		}

		return List.copyOf(definitions);
	}

	private Optional<EmoteDefinition> readDefinition(Path packPath, Path namespacePath) {
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
		int partCount = countParts(createFunctionPath);
		return Optional.of(new EmoteDefinition(namespace, packPath, partCount, animations));
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

	private int countParts(Path createFunctionPath) {
		try (Stream<String> lineStream = Files.lines(createFunctionPath)) {
			return (int) lineStream
				.filter(line -> line.contains("summon item_display"))
				.count();
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to count parts from {}", createFunctionPath, exception);
			return 0;
		}
	}

	private Comparator<Path> pathComparator() {
		return Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
	}
}
