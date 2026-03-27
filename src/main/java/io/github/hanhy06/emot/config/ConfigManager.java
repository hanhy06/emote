package io.github.hanhy06.emot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.hanhy06.emot.Emote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ConfigManager {
	public static ConfigManager INSTANCE;
	public final Object LOCK_KEY = new Object();

	private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
	private static final String CONFIG_FILE_NAME = "config.json";

	private final Path configDirPath;
	private final Gson gson = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();
	private final List<ConfigListener> listeners = new ArrayList<>();

	private Config config = Config.createDefault();

	public ConfigManager(Path configBasePath) {
		INSTANCE = this;
		this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);

		try {
			if (!Files.exists(this.configDirPath)) {
				Files.createDirectories(this.configDirPath);
			}

			writeIfAbsent();
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to create config files. Using default settings.", exception);
		}
	}

	public static Config getConfig() {
		return INSTANCE.config;
	}

	public boolean readConfig() {
		Path configFilePath = this.configDirPath.resolve(CONFIG_FILE_NAME);

		try (BufferedReader reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
			Config loadedConfig = this.gson.fromJson(reader, Config.class);
			Config defaultConfig = Config.createDefault();

			if (loadedConfig == null) {
				Emote.LOGGER.warn("Config is empty or invalid. Keeping current config.");
				broadcastConfig();
				return false;
			}

			if (!Objects.equals(loadedConfig.version(), defaultConfig.version())) {
				Emote.LOGGER.warn("Config version mismatch. Keeping current config.");
				broadcastConfig();
				return false;
			}

			String validationError = validateConfig(loadedConfig);
			if (validationError != null) {
				Emote.LOGGER.warn("Config validation failed: {}. Keeping current config.", validationError);
				broadcastConfig();
				return false;
			}

			this.config = loadedConfig;
			broadcastConfig();
			Emote.LOGGER.info("Config loaded successfully.");
			return true;
		} catch (IOException | RuntimeException exception) {
			Emote.LOGGER.warn("Failed to read config. Keeping current config.", exception);
			broadcastConfig();
			return false;
		}
	}

	public void writeConfig() {
		synchronized (this.LOCK_KEY) {
			writeJsonFile(this.config);
		}
	}

	public void addListener(ConfigListener listener) {
		this.listeners.add(listener);
	}

	public void broadcastConfig() {
		for (ConfigListener listener : this.listeners) {
			listener.onConfigReload(this.config);
		}
	}

	private void writeIfAbsent() {
		Path configFilePath = this.configDirPath.resolve(CONFIG_FILE_NAME);
		if (Files.exists(configFilePath)) {
			return;
		}

		writeJsonFile(this.config);
	}

	private void writeJsonFile(Config config) {
		Path configFilePath = this.configDirPath.resolve(CONFIG_FILE_NAME);

		try (BufferedWriter writer = Files.newBufferedWriter(
			configFilePath,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			this.gson.toJson(config, writer);
			Emote.LOGGER.info("Saved {}", CONFIG_FILE_NAME);
		} catch (IOException exception) {
			Emote.LOGGER.error("Failed to write {}: {}", CONFIG_FILE_NAME, exception.getMessage());
		}
	}

	private String validateConfig(Config config) {
		if (config.version() == null) return "version is missing";
		if (config.menu_page_size() < 1) return "menu_page_size must be at least 1";
		if (config.emote_permissions() == null) return "emote_permissions is missing";

		for (Map.Entry<String, String> entry : config.emote_permissions().entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) return "emote_permissions contains an empty key";
			if (entry.getValue() == null) return "emote_permissions contains a null value";
		}

		return null;
	}
}
