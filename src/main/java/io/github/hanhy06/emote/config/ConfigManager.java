package io.github.hanhy06.emote.config;

import com.google.gson.*;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.io.JsonFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
            Files.createDirectories(this.configDirPath);
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create config files. Using default settings.", exception);
            return;
        }

        writeIfAbsent(CONFIG_FILE_NAME, createConfigJson(this.config));
        writeIfAbsent(PACK_FILE_NAME, createPackConfigJson(this.packConfig));
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

        LinkedHashSet<String> nextDisabled = new LinkedHashSet<>(this.packConfig.disabled());
        if (enabled) {
            nextDisabled.remove(normalizedNamespace);
        } else {
            nextDisabled.add(normalizedNamespace);
        }
        PackConfig nextPackConfig = new PackConfig(List.copyOf(nextDisabled), this.packConfig.permissions());

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
        return object;
    }

    private JsonObject createPackConfigJson(PackConfig packConfig) {
        JsonObject object = new JsonObject();
        JsonArray disabledJson = new JsonArray();
        packConfig.disabled().forEach(disabledJson::add);
        object.add("disabled", disabledJson);
        JsonObject permissionsJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : packConfig.permissions().entrySet()) {
            JsonArray namespacesJson = new JsonArray();
            entry.getValue().forEach(namespacesJson::add);
            permissionsJson.add(entry.getKey(), namespacesJson);
        }
        object.add("permissions", permissionsJson);
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
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineSkinPollIntervalSeconds())
        );
    }

    private PackConfig readPackConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        JsonElement disabledElement = object.get("disabled");
        if (disabledElement != null && !disabledElement.isJsonNull() && !disabledElement.isJsonArray()) {
            return null;
        }

        List<String> disabled = new ArrayList<>();
        if (disabledElement != null && !disabledElement.isJsonNull()) {
            for (JsonElement namespaceElement : disabledElement.getAsJsonArray()) {
                if (!namespaceElement.isJsonPrimitive() || !namespaceElement.getAsJsonPrimitive().isString()) {
                    return null;
                }
                String namespace = normalizeRequiredValue(namespaceElement.getAsString());
                if (namespace == null) {
                    return null;
                }
                disabled.add(namespace);
            }
        }

        JsonElement permissionsElement = object.get("permissions");
        if (permissionsElement != null && !permissionsElement.isJsonNull() && !permissionsElement.isJsonObject()) {
            return null;
        }

        LinkedHashMap<String, List<String>> permissions = new LinkedHashMap<>();
        if (permissionsElement != null && !permissionsElement.isJsonNull()) {
            for (Map.Entry<String, JsonElement> entry : permissionsElement.getAsJsonObject().entrySet()) {
                String permission = normalizeRequiredValue(entry.getKey());
                if (permission == null || permissions.containsKey(permission) || !entry.getValue().isJsonArray()) {
                    return null;
                }

                List<String> namespaces = new ArrayList<>();
                for (JsonElement namespaceElement : entry.getValue().getAsJsonArray()) {
                    if (!namespaceElement.isJsonPrimitive() || !namespaceElement.getAsJsonPrimitive().isString()) {
                        return null;
                    }
                    String namespace = normalizeRequiredValue(namespaceElement.getAsString());
                    if (namespace == null) {
                        return null;
                    }
                    namespaces.add(namespace);
                }
                permissions.put(permission, namespaces);
            }
        }

        return new PackConfig(disabled, permissions);
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
