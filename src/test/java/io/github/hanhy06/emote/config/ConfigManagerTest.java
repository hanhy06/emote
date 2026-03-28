package io.github.hanhy06.emote.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
	@Test
	void constructorCreatesPackJson(@TempDir Path tempDir) {
		new ConfigManager(tempDir);

		assertTrue(Files.exists(tempDir.resolve("emote").resolve("config.json")));
		assertTrue(Files.exists(tempDir.resolve("emote").resolve("pack.json")));
	}

	@Test
	void readPackConfigLoadsConfiguredPacks(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
				  "version": "%s",
				  "permissions": {
				    "emote.pack.vip": [
				      {
				        "datapack_identifier": "wave_pack",
				        "name": "Wave",
				        "command_name": "wave",
				        "description": "Friendly wave",
				        "default_animation_name": "default"
				      }
				    ]
				  }
				}
				""".formatted(PackConfig.createDefault().version())
		);

		assertTrue(manager.readPackConfig());
		assertEquals(List.of("emote.pack.vip"), List.copyOf(manager.getPackConfig().permissions().keySet()));
		assertEquals("wave_pack", manager.getPackConfig().permissions().get("emote.pack.vip").get(0).datapack_identifier());
	}

	@Test
	void readPackConfigRejectsDuplicateNamespace(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
				  "version": "%s",
				  "permissions": {
				    "": [
				      {
				        "datapack_identifier": "wave_pack",
				        "name": "Wave",
				        "command_name": "wave",
				        "description": "Friendly wave",
				        "default_animation_name": "default"
				      }
				    ],
				    "emote.pack.vip": [
				      {
				        "datapack_identifier": "wave_pack",
				        "name": "Wave VIP",
				        "command_name": "wavevip",
				        "description": "Friendly wave vip",
				        "default_animation_name": "default"
				      }
				    ]
				  }
				}
				""".formatted(PackConfig.createDefault().version())
		);

		assertFalse(manager.readPackConfig());
		assertTrue(manager.getPackConfig().permissions().isEmpty());
	}

	@Test
	void readPackConfigRejectsBlankPackField(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
				  "version": "%s",
				  "permissions": {
				    "": [
				      {
				        "datapack_identifier": "wave_pack",
				        "name": " ",
				        "command_name": "wave",
				        "description": "Friendly wave",
				        "default_animation_name": "default"
				      }
				    ]
				  }
				}
				""".formatted(PackConfig.createDefault().version())
		);

		assertFalse(manager.readPackConfig());
		assertTrue(manager.getPackConfig().permissions().isEmpty());
	}
}
