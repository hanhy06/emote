package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import io.github.hanhy06.emote.io.JsonFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (loadedConfig == null) {
            Emote.LOGGER.warn("Config is empty or invalid. Keeping current config.");
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

    public boolean setPackEnabled(String namespace, boolean enabled) {
        String normalizedNamespace = normalizeRequiredValue(namespace);
        if (normalizedNamespace == null) {
            throw new IllegalArgumentException("pack namespace must not be blank");
        }

        LinkedHashMap<String, PackOverride> nextPacks = new LinkedHashMap<>(this.packConfig.packs());
        PackOverride currentOverride = nextPacks.get(normalizedNamespace);
        String permission = currentOverride == null ? "" : currentOverride.permission();
        nextPacks.put(normalizedNamespace, new PackOverride(enabled, permission));
        PackConfig nextPackConfig = new PackConfig(nextPacks);

        if (!writeJsonFile(PACK_FILE_NAME, createPackConfigJson(nextPackConfig))) {
            return false;
        }

        this.packConfig = nextPackConfig;
        broadcastPackConfig();
        return true;
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
        try {
            return JsonFileStore.readObject(filePath);
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

    private boolean writeJsonFile(String fileName, JsonObject json) {
        Path filePath = this.configDirPath.resolve(fileName);

        try {
            JsonFileStore.writeObjectAtomically(filePath, json, this.gson);
            Emote.LOGGER.info("Saved {}", fileName);
            return true;
        } catch (IOException exception) {
            Emote.LOGGER.error("Failed to write {}: {}", fileName, exception.getMessage());
            return false;
        }
    }

    private JsonObject createConfigJson(Config config) {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", config.schemaVersion());
        object.addProperty("menu_page_size", config.menuPageSize());
        object.addProperty("mineskin_api_key", config.mineSkinApiKey());
        object.addProperty("mineskin_poll_interval_seconds", config.mineSkinPollIntervalSeconds());
        object.addProperty("emote_permission", config.emotePermission());
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

    private Config readConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        Config defaultConfig = Config.createDefault();
        return new Config(
            readInt(object, "schema_version", Config.CURRENT_SCHEMA_VERSION),
            readInt(object, "menu_page_size", defaultConfig.menuPageSize()),
            readString(object, "mineskin_api_key", defaultConfig.mineSkinApiKey()),
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineSkinPollIntervalSeconds()),
            readString(object, "emote_permission", defaultConfig.emotePermission())
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
                readEnabled(overrideObject),
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

    private boolean readEnabled(JsonObject object) {
        JsonElement element = object.get("enabled");
        if (element == null || element.isJsonNull()) {
            return true;
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
