package io.github.hanhy06.emote.config;

import com.google.gson.*;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import io.github.hanhy06.emote.io.JsonFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigManager {
    private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String EMOTE_ACCESS_FILE_NAME = "emotes.json";
    private static final String ANIMATION_DIRECTORY_NAME = "animations";

    private final Path configDirPath;
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private final List<ConfigListener> listeners = new ArrayList<>();
    private final List<EmoteAccessConfigListener> emoteAccessListeners = new ArrayList<>();

    private Config config = Config.createDefault();
    private EmoteAccessConfig emoteAccessConfig = EmoteAccessConfig.createDefault();

    public ConfigManager(Path configBasePath) {
        this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);

        try {
            Files.createDirectories(this.configDirPath);
            Files.createDirectories(getAnimationDirectory());
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create config files. Using default settings.", exception);
            return;
        }

        writeIfAbsent(CONFIG_FILE_NAME, createConfigJson(this.config));
        writeIfAbsent(EMOTE_ACCESS_FILE_NAME, createEmoteAccessConfigJson(this.emoteAccessConfig));
    }

    public Config getConfig() {
        return this.config;
    }

    public EmoteAccessConfig getEmoteAccessConfig() {
        return this.emoteAccessConfig;
    }

    public Path getAnimationDirectory() {
        return this.configDirPath.resolve(ANIMATION_DIRECTORY_NAME);
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

    public boolean readEmoteAccessConfig() {
        JsonObject configJson = readJsonFile(EMOTE_ACCESS_FILE_NAME);
        EmoteAccessConfig loadedConfig;
        try {
            loadedConfig = readEmoteAccessConfig(configJson);
        } catch (RuntimeException exception) {
            Emote.LOGGER.warn("Emote access config contains invalid field values. Keeping current config.", exception);
            broadcastEmoteAccessConfig();
            return false;
        }

        if (loadedConfig == null) {
            Emote.LOGGER.warn("Emote access config is empty or invalid. Keeping current config.");
            broadcastEmoteAccessConfig();
            return false;
        }

        this.emoteAccessConfig = loadedConfig;
        broadcastEmoteAccessConfig();
        logLoaded(EMOTE_ACCESS_FILE_NAME);
        return true;
    }

    public void addListener(ConfigListener listener) {
        this.listeners.add(listener);
    }

    public void addEmoteAccessListener(EmoteAccessConfigListener listener) {
        this.emoteAccessListeners.add(listener);
    }

    public boolean setEmoteEnabled(String id, boolean enabled) {
        String normalizedId = normalizeRequiredValue(id);
        if (normalizedId == null) {
            throw new IllegalArgumentException("emote id must not be blank");
        }

        LinkedHashSet<String> nextDisabled = new LinkedHashSet<>(this.emoteAccessConfig.disabled());
        if (enabled) {
            nextDisabled.remove(normalizedId);
        } else {
            nextDisabled.add(normalizedId);
        }
        EmoteAccessConfig nextConfig = new EmoteAccessConfig(
            List.copyOf(nextDisabled),
            this.emoteAccessConfig.permissions()
        );

        if (!writeJsonFile(EMOTE_ACCESS_FILE_NAME, createEmoteAccessConfigJson(nextConfig))) {
            return false;
        }

        this.emoteAccessConfig = nextConfig;
        broadcastEmoteAccessConfig();
        return true;
    }

    private void broadcastConfig() {
        for (ConfigListener listener : this.listeners) {
            listener.onConfigReload(this.config);
        }
    }

    private void broadcastEmoteAccessConfig() {
        for (EmoteAccessConfigListener listener : this.emoteAccessListeners) {
            listener.onEmoteAccessConfigReload(this.emoteAccessConfig);
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

    private JsonObject createEmoteAccessConfigJson(EmoteAccessConfig config) {
        JsonObject object = new JsonObject();
        JsonArray disabledJson = new JsonArray();
        config.disabled().forEach(disabledJson::add);
        object.add("disabled", disabledJson);
        JsonObject permissionsJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : config.permissions().entrySet()) {
            JsonArray idsJson = new JsonArray();
            entry.getValue().forEach(idsJson::add);
            permissionsJson.add(entry.getKey(), idsJson);
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
            readMineSkinApiKey(object, defaultConfig.mineSkinApiKey()),
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineSkinPollIntervalSeconds())
        );
    }

    private EmoteAccessConfig readEmoteAccessConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        JsonElement disabledElement = object.get("disabled");
        if (disabledElement != null && !disabledElement.isJsonNull() && !disabledElement.isJsonArray()) {
            return null;
        }

        List<String> disabled = new ArrayList<>();
        if (disabledElement != null && !disabledElement.isJsonNull()) {
            for (JsonElement idElement : disabledElement.getAsJsonArray()) {
                if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                    return null;
                }
                String id = normalizeRequiredValue(idElement.getAsString());
                if (id == null) {
                    return null;
                }
                disabled.add(id);
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

                List<String> ids = new ArrayList<>();
                for (JsonElement idElement : entry.getValue().getAsJsonArray()) {
                    if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                        return null;
                    }
                    String id = normalizeRequiredValue(idElement.getAsString());
                    if (id == null) {
                        return null;
                    }
                    ids.add(id);
                }
                permissions.put(permission, ids);
            }
        }

        return new EmoteAccessConfig(disabled, permissions);
    }

    private String readMineSkinApiKey(JsonObject object, String defaultValue) {
        JsonElement element = object.get("mineskin_api_key");
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
