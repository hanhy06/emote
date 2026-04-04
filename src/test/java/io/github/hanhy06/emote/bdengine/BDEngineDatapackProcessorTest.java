package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.data.IdentifierConfig;
import io.github.hanhy06.emote.config.data.IdentifierEntry;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BDEngineDatapackProcessorTest {
	@Test
	void readDefinitionsUsesIdentifierConfigMetadata(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, createIdentifierConfig(
			"",
			new IdentifierEntry("wave_pack", "Wave", "wave", "Friendly wave", "idle")
		));

		assertEquals(1, definitions.size());
		assertEquals("wave_pack", definitions.get(0).namespace());
		assertEquals("Wave", definitions.get(0).name());
		assertEquals("Friendly wave", definitions.get(0).description());
		assertEquals("wave", definitions.get(0).commandName());
		assertEquals("idle", definitions.get(0).defaultAnimationName());
	}

	@Test
	void readDefinitionsAppendsLoopAnimationsWhenConfigured(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack", true);

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, createIdentifierConfig(
			"",
			new IdentifierEntry("wave_pack", "Wave", "wave", "Friendly wave", "default", "loop")
		));

		assertEquals(List.of("default", "default_loop"), definitions.get(0).animations().stream().map(animation -> animation.name()).toList());
	}

	@Test
	void findIdentifierPackIdsKeepsOnlyConfiguredNamespaces(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack");
		createDatapack(datapackDirPath.resolve("beta_pack"), "bow_pack");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<String> packIds = processor.findIdentifierPackIds(datapackDirPath, createIdentifierConfig(
			"",
			new IdentifierEntry("wave_pack", "Wave", "wave", "Friendly wave", "default")
		));

		assertEquals(List.of("file/alpha_pack"), packIds);
	}

	private IdentifierConfig createIdentifierConfig(String permission, IdentifierEntry... identifierEntries) {
		LinkedHashMap<String, List<IdentifierEntry>> permissions = new LinkedHashMap<>();
		permissions.put(permission, List.of(identifierEntries));
		return new IdentifierConfig(permissions);
	}

	private void createDatapack(Path packPath, String namespace) throws IOException {
		createDatapack(packPath, namespace, false);
	}

	private void createDatapack(Path packPath, String namespace, boolean createLoopFunction) throws IOException {
		Files.createDirectories(packPath);
		Files.writeString(packPath.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":61,\"description\":\"test\"}}");
		Files.createDirectories(packPath.resolve("data").resolve(namespace).resolve("function").resolve("_"));
		Files.createDirectories(packPath.resolve("data").resolve(namespace).resolve("function").resolve("a").resolve("default"));
		Files.writeString(packPath.resolve("data").resolve(namespace).resolve("function").resolve("_").resolve("create.mcfunction"), "");
		Files.writeString(packPath.resolve("data").resolve(namespace).resolve("function").resolve("a").resolve("default").resolve("play_anim.mcfunction"), "");
		if (createLoopFunction) {
			Files.writeString(packPath.resolve("data").resolve(namespace).resolve("function").resolve("a").resolve("default").resolve("play_anim_loop.mcfunction"), "");
		}
	}
}
