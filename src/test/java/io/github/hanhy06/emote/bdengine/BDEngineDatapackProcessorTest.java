package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;
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

	@Test
	void readDefinitionsRejectsDuplicateNamespacesAcrossDatapacks(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		createDatapack(datapackDirPath.resolve("alpha_pack"), "wave_pack");
		createDatapack(datapackDirPath.resolve("beta_pack"), "wave_pack");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(List.of(), definitions);
	}

	@Test
	void readDefinitionsRejectsDuplicateCommandNames(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("bundle");
		createDatapack(packPath, "wave_pack");
		createEmote(packPath, "bow_pack", "Bow", "wave");

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteDefinition> definitions = processor.readDefinitions(datapackDirPath, PackConfig.createDefault());

		assertEquals(List.of(), definitions);
	}

	@Test
	void readDefinitionsAssignsRaisedArmSkinFromShoulderToHand(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("dance_pack");
		createDatapack(packPath, "dance");
		Path createFunctionPath = packPath.resolve("data/dance/function/_/create.mcfunction");
		Files.writeString(createFunctionPath, String.join("\n",
			createPlayerHead("dance", 1, "body", 0.0D, 1.5D, 0.0D, 0.5D),
			createPlayerHead("dance", 11, "left_arm", 0.5000000001D, 1.0D, 0.0D, 0.25D),
			createPlayerHead("dance", 12, "left_arm", 0.5D, 1.0D, 0.0D, 0.5D),
			createPlayerHead("dance", 13, "left_arm", 0.2D, 1.6D, 0.0D, 0.5D),
			createPlayerHead("dance", 14, "left_arm", 0.4D, 1.3D, 0.0D, 0.25D)
		));

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteSkinPart> skinParts = processor.readDefinitions(datapackDirPath, PackConfig.createDefault())
			.getFirst()
			.skinParts();

		assertEquals(new PlayerSkinSegment(0, 4), findSegment(skinParts, 13, PlayerSkinPart.LEFT_ARM));
		assertEquals(new PlayerSkinSegment(4, 6), findSegment(skinParts, 14, PlayerSkinPart.LEFT_ARM));
		assertEquals(new PlayerSkinSegment(6, 8), findSegment(skinParts, 11, PlayerSkinPart.LEFT_ARM));
		assertEquals(new PlayerSkinSegment(8, 12), findSegment(skinParts, 12, PlayerSkinPart.LEFT_ARM));
	}

	@Test
	void readDefinitionsKeepsEquivalentLeftLegAnchorsInPartOrder(@TempDir Path tempDir) throws IOException {
		Path datapackDirPath = Files.createDirectories(tempDir.resolve("datapacks"));
		Path packPath = datapackDirPath.resolve("dance_pack");
		createDatapack(packPath, "dance");
		Path createFunctionPath = packPath.resolve("data/dance/function/_/create.mcfunction");
		Files.writeString(createFunctionPath, String.join("\n",
			createPlayerHead("dance", 1, "body", 0.0D, 1.5D, 0.0D, 0.5D),
			createPlayerHead("dance", 7, "left_leg", 0.5000000001D, 0.5D, 0.0D, 0.25D),
			createPlayerHead("dance", 8, "left_leg", 0.5D, 0.5D, 0.0D, 0.5D),
			createPlayerHead("dance", 9, "left_leg", 0.2D, 1.2D, 0.0D, 0.5D),
			createPlayerHead("dance", 10, "left_leg", 0.4D, 0.8D, 0.0D, 0.25D)
		));

		BDEngineDatapackProcessor processor = new BDEngineDatapackProcessor(new ConfigManager(tempDir), new EmoteRegistry());
		List<EmoteSkinPart> skinParts = processor.readDefinitions(datapackDirPath, PackConfig.createDefault())
			.getFirst()
			.skinParts();

		assertEquals(new PlayerSkinSegment(0, 4), findSegment(skinParts, 9, PlayerSkinPart.LEFT_LEG));
		assertEquals(new PlayerSkinSegment(4, 6), findSegment(skinParts, 10, PlayerSkinPart.LEFT_LEG));
		assertEquals(new PlayerSkinSegment(6, 8), findSegment(skinParts, 7, PlayerSkinPart.LEFT_LEG));
		assertEquals(new PlayerSkinSegment(8, 12), findSegment(skinParts, 8, PlayerSkinPart.LEFT_LEG));
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

	private String createPlayerHead(
		String namespace,
		int partIndex,
		String skinPart,
		double anchorX,
		double anchorY,
		double anchorZ,
		double scaleY
	) {
		return "summon item_display ~ ~ ~ {id:\"minecraft:item_display\",item:{id:\"minecraft:player_head\",components:{\"minecraft:profile\":{name:\"emote:%s\"}}},transformation:[1f,0f,0f,%sf,0f,%sf,0f,%sf,0f,0f,1f,%sf,0f,0f,0f,1f],Tags:[\"%s_%s\"]}"
			.formatted(skinPart, anchorX, scaleY, anchorY - scaleY * 0.5D, anchorZ, namespace, partIndex);
	}

	private PlayerSkinSegment findSegment(List<EmoteSkinPart> skinParts, int partIndex, PlayerSkinPart skinPart) {
		return skinParts.stream()
			.filter(part -> part.partIndex() == partIndex)
			.filter(part -> part.skinPart() == skinPart)
			.findFirst()
			.orElseThrow()
			.skinSegment();
	}
}
