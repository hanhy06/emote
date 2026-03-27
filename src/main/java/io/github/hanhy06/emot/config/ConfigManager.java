package io.github.hanhy06.emot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class ConfigManager {
	public static ConfigManager INSTANCE;
	public final Object LOCK_KEY = new Object();

	private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
	private static final String CONFIG_FILE_NAME = "config.json";
	private static final String DATAPACK_DIR_NAME = "datapack";
	private static final String DATAPACK_EXAMPLE_FILE_NAME = "emote-datapack.example.json";

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
			writeDatapackExample();
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
			JsonElement element = JsonParser.parseReader(reader);
			Config loadedConfig = readConfig(element);
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
			Emote.LOGGER.info("Config loaded");
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

	private void writeDatapackExample() throws IOException {
		Path datapackDirPath = this.configDirPath.resolve(DATAPACK_DIR_NAME);
		if (!Files.exists(datapackDirPath)) {
			Files.createDirectories(datapackDirPath);
		}

		Path exampleFilePath = datapackDirPath.resolve(DATAPACK_EXAMPLE_FILE_NAME);
		LinkedHashMap<String, Object> exampleJson = new LinkedHashMap<>();
		exampleJson.put("name", "Example Emote");
		exampleJson.put("description", "Shown in the emote dialog.");

		try (BufferedWriter writer = Files.newBufferedWriter(
			exampleFilePath,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			this.gson.toJson(exampleJson, writer);
			Emote.LOGGER.info("Saved {}", this.configDirPath.relativize(exampleFilePath));
		}
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
		if (config.emote_permission() == null) return "emote_permission is missing";
		if (config.emote_permissions() == null) return "emote_permissions is missing";

		for (Map.Entry<String, String> entry : config.emote_permissions().entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) return "emote_permissions contains an empty key";
			if (entry.getValue() == null) return "emote_permissions contains a null value";
		}

		return null;
	}

	private Config readConfig(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}

		JsonObject object = element.getAsJsonObject();
		Config defaultConfig = Config.createDefault();
		return new Config(
			readString(object, "version", defaultConfig.version()),
			readInt(object, "menu_page_size", defaultConfig.menu_page_size()),
			readBoolean(object, "quick_action_enabled", defaultConfig.quick_action_enabled()),
			readEmotePermission(object, defaultConfig.emote_permission()),
			readPermissionMap(object)
		);
	}

	private String readEmotePermission(JsonObject object, String defaultValue) {
		String emotePermission = readOptionalString(object, "emote_permission");
		return emotePermission == null ? defaultValue : emotePermission;
	}

	private LinkedHashMap<String, String> readPermissionMap(JsonObject object) {
		LinkedHashMap<String, String> emotePermissionMap = new LinkedHashMap<>();
		JsonElement element = object.get("emote_permissions");
		if (element == null || !element.isJsonObject()) {
			return emotePermissionMap;
		}

		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			emotePermissionMap.put(entry.getKey(), entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
		}

		return emotePermissionMap;
	}

	private String readString(JsonObject object, String key, String defaultValue) {
		String value = readOptionalString(object, key);
		return value == null ? defaultValue : value;
	}

	private String readOptionalString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}

		return element.getAsString();
	}

	private int readInt(JsonObject object, String key, int defaultValue) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return defaultValue;
		}

		return element.getAsInt();
	}

	private boolean readBoolean(JsonObject object, String key, boolean defaultValue) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return defaultValue;
		}

		return element.getAsBoolean();
	}
}
