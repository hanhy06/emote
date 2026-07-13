package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
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
	void readDefinitionsIgnoresPackWithoutEmoteMetadata(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("alpha_pack");
		createDatapack(packPath, "wave_pack");
		Files.delete(packPath.resolve("data/wave_pack/emote.json"));

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(List.of(), definitions);
	}

	@Test
	void readDefinitionsUsesDatapackMetadata(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(1, definitions.size());
		assertEquals("wave_pack", definitions.get(0).namespace());
		assertEquals("Wave", definitions.get(0).name());
		assertEquals("Friendly wave", definitions.get(0).description());
		assertEquals("wave", definitions.get(0).commandName());
		assertEquals("a/default/play_anim_loop", definitions.get(0).entrypoint());
		assertEquals(false, definitions.get(0).hidePlayer());
	}

	@Test
	void readDefinitionsLoadsMultipleNamespaceEmotesFromOneDatapack(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("bundle");
		createDatapack(packPath, "wave_pack");
		createEmote(packPath, "bow_pack", "Bow", "bow");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(List.of("bow_pack", "wave_pack"), definitions.stream().map(EmoteDefinition::namespace).toList());
		assertEquals(List.of("Bow", "Wave"), definitions.stream().map(EmoteDefinition::name).toList());
	}

	@Test
	void readDefinitionsRejectsMissingEntrypoint(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("alpha_pack");
		createDatapack(packPath, "wave_pack");
		Files.delete(packPath.resolve("data/wave_pack/function/a/default/play_anim_loop.mcfunction"));

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(List.of(), definitions);
	}

	@Test
	void findEmotePackIdsSkipsDisabledNamespaces(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack");
		createDatapack(datapackDirPath.resolve("beta_pack"), "bow_pack");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		LinkedHashMap<String, PackOverride> packs = new LinkedHashMap<>();
		packs.put("bow_pack", new PackOverride(false, ""));
		List<String> packIds = processor.findEmotePackIds(datapackDirPath, new PackConfig(packs));

		assertEquals(List.of("file/alpha_pack"), packIds);
	}

	private void createDatapack(Path packPath, String namespace) throws IOException {
		Files.createDirectories(packPath);
		Files.writeString(packPath.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":61,\"description\":\"test\"}}");
		createEmote(packPath, namespace, "Wave", "wave");
	}

	private void createEmote(Path packPath, String namespace, String name, String commandName) throws IOException {
		Path namespacePath = Files.createDirectories(packPath.resolve("data").resolve(namespace));
		Files.writeString(namespacePath.resolve("emote.json"), """
			{
			  "schema_version": 3,
			  "name": "%s",
			  "description": "Friendly wave",
			  "command_name": "%s",
			  "entrypoint": "a/default/play_anim_loop",
			  "hide_player": false
			}
			""".formatted(name, commandName));
		Files.createDirectories(packPath.resolve("data").resolve(namespace).resolve("function").resolve("_"));
		Files.createDirectories(packPath.resolve("data").resolve(namespace).resolve("function").resolve("a").resolve("default"));
		Files.writeString(packPath.resolve("data").resolve(namespace).resolve("function").resolve("_").resolve("create.mcfunction"), "");
		Files.writeString(packPath.resolve("data").resolve(namespace).resolve("function").resolve("a").resolve("default").resolve("play_anim_loop.mcfunction"), "");
	}
}
