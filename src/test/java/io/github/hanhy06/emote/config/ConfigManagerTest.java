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
	void constructorCreatesPackJson(@TempDir Path tempDir) throws IOException {
		new ConfigManager(tempDir);

		assertTrue(Files.exists(tempDir.resolve("emote").resolve("config.json")));
		assertTrue(Files.exists(tempDir.resolve("emote").resolve("pack.json")));
		assertFalse(Files.readString(tempDir.resolve("emote").resolve("pack.json")).contains("\"version\""));
	}

	@Test
	void readIdentifierConfigLoadsConfiguredPacks(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
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
				"""
		);

		assertTrue(manager.readIdentifierConfig());
		assertEquals(List.of("emote.pack.vip"), List.copyOf(manager.getIdentifierConfig().permissions().keySet()));
		assertEquals("wave_pack", manager.getIdentifierConfig().permissions().get("emote.pack.vip").get(0).datapack_identifier());
	}

	@Test
	void readIdentifierConfigRejectsDuplicateNamespace(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
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
				"""
		);

		assertFalse(manager.readIdentifierConfig());
		assertTrue(manager.getIdentifierConfig().permissions().isEmpty());
	}

	@Test
	void readIdentifierConfigRejectsBlankIdentifierField(@TempDir Path tempDir) throws IOException {
		ConfigManager manager = new ConfigManager(tempDir);
		Files.writeString(
			tempDir.resolve("emote").resolve("pack.json"),
			"""
				{
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
				"""
		);

		assertFalse(manager.readIdentifierConfig());
		assertTrue(manager.getIdentifierConfig().permissions().isEmpty());
	}
}
