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
    private final ConfigJsonCodec jsonCodec = new ConfigJsonCodec();
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

        writeIfAbsent(CONFIG_FILE_NAME, this.jsonCodec.writeConfig(this.config));
        writeIfAbsent(EMOTE_ACCESS_FILE_NAME, this.jsonCodec.writeEmoteAccessConfig(this.emoteAccessConfig));
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
            loadedConfig = this.jsonCodec.readConfig(configJson);
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
            loadedConfig = this.jsonCodec.readEmoteAccessConfig(configJson);
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

        if (!writeJsonFile(EMOTE_ACCESS_FILE_NAME, this.jsonCodec.writeEmoteAccessConfig(nextConfig))) {
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
