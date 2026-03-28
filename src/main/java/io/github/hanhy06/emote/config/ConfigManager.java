package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ConfigManager {
	public static ConfigManager INSTANCE;

	private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
	private static final String CONFIG_FILE_NAME = "config.json";
	private static final String PACK_FILE_NAME = "pack.json";

	private final Path configDirPath;
	private final Object writeLock = new Object();
	private final Gson gson = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();
	private final List<ConfigListener> listeners = new ArrayList<>();
	private final List<PackConfigListener> packListeners = new ArrayList<>();

	private Config config = Config.createDefault();
	private PackConfig packConfig = PackConfig.createDefault();

	public ConfigManager(Path configBasePath) {
		INSTANCE = this;
		this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);

		try {
			if (!Files.exists(this.configDirPath)) {
				Files.createDirectories(this.configDirPath);
			}

			writeIfAbsent(CONFIG_FILE_NAME, createConfigJson(this.config));
			writeIfAbsent(PACK_FILE_NAME, createPackConfigJson(this.packConfig));
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to create config files. Using default settings.", exception);
		}
	}

	public Config getConfig() {
		return this.config;
	}

	public PackConfig getPackConfig() {
		return this.packConfig;
	}

	public boolean readConfig() {
		try (BufferedReader reader = Files.newBufferedReader(this.configDirPath.resolve(CONFIG_FILE_NAME), StandardCharsets.UTF_8)) {
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
			Emote.LOGGER.info("Loaded {}", CONFIG_FILE_NAME);
			return true;
		} catch (IOException | RuntimeException exception) {
			Emote.LOGGER.warn("Failed to read config. Keeping current config.", exception);
			broadcastConfig();
			return false;
		}
	}

	public boolean readPackConfig() {
		try (BufferedReader reader = Files.newBufferedReader(this.configDirPath.resolve(PACK_FILE_NAME), StandardCharsets.UTF_8)) {
			JsonElement element = JsonParser.parseReader(reader);
			PackConfig loadedPackConfig = readPackConfig(element);
			PackConfig defaultPackConfig = PackConfig.createDefault();

			if (loadedPackConfig == null) {
				Emote.LOGGER.warn("Pack config is empty or invalid. Keeping current pack config.");
				broadcastPackConfig();
				return false;
			}

			if (!Objects.equals(loadedPackConfig.version(), defaultPackConfig.version())) {
				Emote.LOGGER.warn("Pack config version mismatch. Keeping current pack config.");
				broadcastPackConfig();
				return false;
			}

			String validationError = validatePackConfig(loadedPackConfig);
			if (validationError != null) {
				Emote.LOGGER.warn("Pack config validation failed: {}. Keeping current pack config.", validationError);
				broadcastPackConfig();
				return false;
			}

			this.packConfig = loadedPackConfig;
			broadcastPackConfig();
			Emote.LOGGER.info("Loaded {}", PACK_FILE_NAME);
			return true;
		} catch (IOException | RuntimeException exception) {
			Emote.LOGGER.warn("Failed to read pack config. Keeping current pack config.", exception);
			broadcastPackConfig();
			return false;
		}
	}

	public void writeConfig() {
		synchronized (this.writeLock) {
			writeJsonFile(CONFIG_FILE_NAME, createConfigJson(this.config));
		}
	}

	public void writePackConfig() {
		synchronized (this.writeLock) {
			writeJsonFile(PACK_FILE_NAME, createPackConfigJson(this.packConfig));
		}
	}

	public void addListener(ConfigListener listener) {
		this.listeners.add(listener);
	}

	public void addPackListener(PackConfigListener listener) {
		this.packListeners.add(listener);
	}

	public void broadcastConfig() {
		for (ConfigListener listener : this.listeners) {
			listener.onConfigReload(this.config);
		}
	}

	public void broadcastPackConfig() {
		for (PackConfigListener listener : this.packListeners) {
			listener.onPackConfigReload(this.packConfig);
		}
	}

	private void writeIfAbsent(String fileName, JsonObject json) {
		Path filePath = this.configDirPath.resolve(fileName);
		if (Files.exists(filePath)) {
			return;
		}

		writeJsonFile(fileName, json);
	}

	private void writeJsonFile(String fileName, JsonObject json) {
		Path filePath = this.configDirPath.resolve(fileName);

		try (BufferedWriter writer = Files.newBufferedWriter(
			filePath,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			this.gson.toJson(json, writer);
			Emote.LOGGER.info("Saved {}", fileName);
		} catch (IOException exception) {
			Emote.LOGGER.error("Failed to write {}: {}", fileName, exception.getMessage());
		}
	}

	private JsonObject createConfigJson(Config config) {
		JsonObject object = new JsonObject();
		object.addProperty("version", config.version());
		object.addProperty("menu_page_size", config.menu_page_size());
		object.addProperty("player_skin_port", config.player_skin_port());
		object.addProperty("emote_permission", config.emote_permission());
		return object;
	}

	private JsonObject createPackConfigJson(PackConfig packConfig) {
		JsonObject object = new JsonObject();
		object.addProperty("version", packConfig.version());

		JsonObject permissionsJson = new JsonObject();
		for (Map.Entry<String, List<EmotePack>> entry : packConfig.permissions().entrySet()) {
			JsonArray packArray = new JsonArray();
			for (EmotePack emotePack : entry.getValue()) {
				JsonObject packJson = new JsonObject();
				packJson.addProperty("datapack_identifier", emotePack.datapack_identifier());
				packJson.addProperty("name", emotePack.name());
				packJson.addProperty("command_name", emotePack.command_name());
				packJson.addProperty("description", emotePack.description());
				packJson.addProperty("default_animation_name", emotePack.default_animation_name());
				packArray.add(packJson);
			}

			permissionsJson.add(entry.getKey(), packArray);
		}

		object.add("permissions", permissionsJson);
		return object;
	}

	private String validateConfig(Config config) {
		if (config.version() == null) return "version is missing";
		if (config.menu_page_size() < 1) return "menu_page_size must be at least 1";
		if (config.player_skin_port() < 0 || config.player_skin_port() > 65535) return "player_skin_port must be between 0 and 65535";
		if (config.emote_permission() == null) return "emote_permission is missing";
		return null;
	}

	private String validatePackConfig(PackConfig packConfig) {
		if (packConfig.version() == null) return "version is missing";
		if (packConfig.permissions() == null) return "permissions is missing";

		Set<String> configuredNamespaces = new HashSet<>();
		for (Map.Entry<String, List<EmotePack>> entry : packConfig.permissions().entrySet()) {
			if (entry.getKey() == null) return "permissions contains a null key";
			if (entry.getValue() == null) return "permissions contains a null pack list";

			for (EmotePack emotePack : entry.getValue()) {
				if (emotePack == null) return "permissions contains a null pack";

				String datapackIdentifier = normalizeRequiredValue(emotePack.datapack_identifier());
				if (datapackIdentifier == null) return "permissions contains a pack with blank datapack_identifier";
				if (!configuredNamespaces.add(datapackIdentifier)) {
					return "permissions contains duplicate datapack_identifier: " + datapackIdentifier;
				}

				if (normalizeRequiredValue(emotePack.name()) == null) return "permissions contains a pack with blank name";
				if (normalizeRequiredValue(emotePack.command_name()) == null) return "permissions contains a pack with blank command_name";
				if (normalizeRequiredValue(emotePack.description()) == null) return "permissions contains a pack with blank description";
				if (normalizeRequiredValue(emotePack.default_animation_name()) == null) {
					return "permissions contains a pack with blank default_animation_name";
				}
			}
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
			readInt(object, "player_skin_port", defaultConfig.player_skin_port()),
			readString(object, "emote_permission", defaultConfig.emote_permission())
		);
	}

	private PackConfig readPackConfig(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}

		JsonObject object = element.getAsJsonObject();
		PackConfig defaultPackConfig = PackConfig.createDefault();
		LinkedHashMap<String, List<EmotePack>> permissions = readPackPermissions(object);
		if (permissions == null) {
			return null;
		}

		return new PackConfig(
			readString(object, "version", defaultPackConfig.version()),
			permissions
		);
	}

	private LinkedHashMap<String, List<EmotePack>> readPackPermissions(JsonObject object) {
		JsonElement permissionsElement = object.get("permissions");
		if (permissionsElement == null || permissionsElement.isJsonNull()) {
			return new LinkedHashMap<>();
		}

		if (!permissionsElement.isJsonObject()) {
			return null;
		}

		LinkedHashMap<String, List<EmotePack>> permissions = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : permissionsElement.getAsJsonObject().entrySet()) {
			String permission = normalizePermissionKey(entry.getKey());
			if (permission == null || permissions.containsKey(permission) || !entry.getValue().isJsonArray()) {
				return null;
			}

			List<EmotePack> packList = readPackList(entry.getValue().getAsJsonArray());
			if (packList == null) {
				return null;
			}

			permissions.put(permission, packList);
		}

		return permissions;
	}

	private List<EmotePack> readPackList(JsonArray packArray) {
		List<EmotePack> packList = new ArrayList<>();
		for (JsonElement element : packArray) {
			EmotePack emotePack = readEmotePack(element);
			if (emotePack == null) {
				return null;
			}

			packList.add(emotePack);
		}

		return List.copyOf(packList);
	}

	private EmotePack readEmotePack(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}

		JsonObject object = element.getAsJsonObject();
		return new EmotePack(
			readString(object, "datapack_identifier", ""),
			readString(object, "name", ""),
			readString(object, "command_name", ""),
			readString(object, "description", ""),
			readString(object, "default_animation_name", "")
		);
	}

	private String readString(JsonObject object, String key, String defaultValue) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return defaultValue;
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

	private String normalizeRequiredValue(String value) {
		if (value == null) {
			return null;
		}

		String normalizedValue = value.trim();
		return normalizedValue.isEmpty() ? null : normalizedValue;
	}

	private String normalizePermissionKey(String permission) {
		return permission == null ? null : permission.trim();
	}
}
