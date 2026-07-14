package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

public class ConfigManager {
    private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String PACK_FILE_NAME = "packs.json";

    private final Path configDirPath;
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private final List<ConfigListener> listeners = new ArrayList<>();
    private final List<PackConfigListener> packListeners = new ArrayList<>();

    private Config config = Config.createDefault();
    private PackConfig packConfig = PackConfig.createDefault();

    public ConfigManager(Path configBasePath) {
        this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);

        try {
            if (!Files.exists(this.configDirPath)) {
                Files.createDirectories(this.configDirPath);
                writeConfig(this::writeJsonFile);
                writePackConfig(this::writeJsonFile);
                return;
            }

            writeConfig(this::writeIfAbsent);
            writePackConfig(this::writeIfAbsent);
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
        JsonObject configJson = readJsonFile(CONFIG_FILE_NAME);
        Config loadedConfig;
        try {
            loadedConfig = readConfig(configJson);
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Config contains invalid field values. Keeping current config.", exception);
            broadcastConfig();
            return false;
        }
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
        logLoaded(CONFIG_FILE_NAME);
        return true;
    }

    public boolean readPackConfig() {
        JsonObject configJson = readJsonFile(PACK_FILE_NAME);
        PackConfig loadedPackConfig;
        try {
            loadedPackConfig = readPackConfig(configJson);
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Pack config contains invalid field values. Keeping current pack config.", exception);
            broadcastPackConfig();
            return false;
        }

        if (loadedPackConfig == null) {
            Emote.LOGGER.warn("Pack config is empty or invalid. Keeping current pack config.");
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
        logLoaded(PACK_FILE_NAME);
        return true;
    }

    public void addListener(ConfigListener listener) {
        this.listeners.add(listener);
    }

    public void addPackListener(PackConfigListener listener) {
        this.packListeners.add(listener);
    }

    private void broadcastConfig() {
        for (ConfigListener listener : this.listeners) {
            listener.onConfigReload(this.config);
        }
    }

    private void broadcastPackConfig() {
        for (PackConfigListener listener : this.packListeners) {
            listener.onPackConfigReload(this.packConfig);
        }
    }

    private JsonObject readJsonFile(String fileName) {
        Path filePath = this.configDirPath.resolve(fileName);
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read {}: {}", fileName, exception.getMessage());
            return null;
        }
    }

    private void writeConfig(BiConsumer<String, JsonObject> writer) {
        writer.accept(CONFIG_FILE_NAME, createConfigJson(this.config));
    }

    private void writePackConfig(BiConsumer<String, JsonObject> writer) {
        writer.accept(PACK_FILE_NAME, createPackConfigJson(this.packConfig));
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
        object.addProperty("mineskin_api_key", config.mineskin_api_key());
        object.addProperty("mineskin_poll_interval_seconds", config.mineskin_poll_interval_seconds());
        object.addProperty("emote_permission", config.emote_permission());
        return object;
    }

    private JsonObject createPackConfigJson(PackConfig packConfig) {
        JsonObject object = new JsonObject();
        JsonObject packsJson = new JsonObject();
        for (Map.Entry<String, PackOverride> entry : packConfig.packs().entrySet()) {
            JsonObject overrideJson = new JsonObject();
            overrideJson.addProperty("enabled", entry.getValue().enabled());
            overrideJson.addProperty("permission", entry.getValue().permission());
            packsJson.add(entry.getKey(), overrideJson);
        }

        object.add("packs", packsJson);
        return object;
    }

    private String validateConfig(Config config) {
        if (config.version() == null) return "version is missing";
        if (config.menu_page_size() < 1) return "menu_page_size must be at least 1";
        if (config.mineskin_api_key() == null) return "mineskin_api_key is missing";
        if (config.mineskin_poll_interval_seconds() < 1 || config.mineskin_poll_interval_seconds() > 60)
            return "mineskin_poll_interval_seconds must be between 1 and 60";
        if (config.emote_permission() == null) return "emote_permission is missing";
        return null;
    }

    private String validatePackConfig(PackConfig packConfig) {
        if (packConfig.packs() == null) return "packs is missing";
        for (Map.Entry<String, PackOverride> entry : packConfig.packs().entrySet()) {
            if (normalizeRequiredValue(entry.getKey()) == null) return "packs contains a blank namespace";
            if (entry.getValue() == null) return "packs contains a null override";
            if (entry.getValue().permission() == null) return "packs contains a null permission";
        }

        return null;
    }

    private Config readConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        Config defaultConfig = Config.createDefault();
        return new Config(
            readString(object, "version", defaultConfig.version()),
            readInt(object, "menu_page_size", defaultConfig.menu_page_size()),
            readString(object, "mineskin_api_key", defaultConfig.mineskin_api_key()),
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineskin_poll_interval_seconds()),
            readString(object, "emote_permission", defaultConfig.emote_permission())
        );
    }

    private PackConfig readPackConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        JsonElement packsElement = object.get("packs");
        if (packsElement == null || packsElement.isJsonNull()) {
            return PackConfig.createDefault();
        }
        if (!packsElement.isJsonObject()) {
            return null;
        }

        LinkedHashMap<String, PackOverride> packs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : packsElement.getAsJsonObject().entrySet()) {
            String namespace = normalizeRequiredValue(entry.getKey());
            if (namespace == null || packs.containsKey(namespace) || !entry.getValue().isJsonObject()) {
                return null;
            }
            JsonObject overrideObject = entry.getValue().getAsJsonObject();
            packs.put(namespace, new PackOverride(
                readBoolean(overrideObject, "enabled", true),
                readString(overrideObject, "permission", "")
            ));
        }

        return new PackConfig(packs);
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

    private boolean readBoolean(JsonObject object, String key, boolean defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }

        return element.getAsBoolean();
    }

    private void logLoaded(String fileName) {
        Emote.LOGGER.info("Loaded {}", fileName);
    }

    private String normalizeRequiredValue(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

}
