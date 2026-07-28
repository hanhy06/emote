package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class ConfigManager {
    private static final String CONFIG_FILE_DIR = Emote.MOD_ID;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String EMOTE_ACCESS_FILE_NAME = "emotes.json";
    private static final String ANIMATION_DIRECTORY_NAME = "animations";
    private static final String BUNDLED_ANIMATION_DIRECTORY_NAME = "default-emote-animations";

    private final Path configDirPath;
    private final @Nullable Path bundledAnimationDirectory;
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
        this(configBasePath, findBundledAnimationDirectory().orElse(null));
    }

    ConfigManager(Path configBasePath, @Nullable Path bundledAnimationDirectory) {
        this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);
        this.bundledAnimationDirectory = bundledAnimationDirectory;
    }

    public void configure() {
        boolean installBundledAnimations = Files.notExists(this.configDirPath);

        try {
            Files.createDirectories(this.configDirPath);
            Files.createDirectories(getAnimationDirectory());
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to create config files. Using default settings.", exception);
            return;
        }

        if (installBundledAnimations) {
            try {
                installBundledAnimations(this.bundledAnimationDirectory);
            } catch (IOException exception) {
                Emote.LOGGER.warn("Failed to install bundled emote animations.", exception);
            }
        }

        writeIfAbsent(CONFIG_FILE_NAME, this.jsonCodec.writeConfig(this.config));
        writeIfAbsent(EMOTE_ACCESS_FILE_NAME, this.jsonCodec.writeEmoteAccessConfig(this.emoteAccessConfig));
    }

    private static Optional<Path> findBundledAnimationDirectory() {
        return FabricLoader.getInstance()
            .getModContainer(Emote.MOD_ID)
            .flatMap(container -> container.findPath(BUNDLED_ANIMATION_DIRECTORY_NAME));
    }

    private void installBundledAnimations(@Nullable Path bundledAnimationDirectory) throws IOException {
        if (bundledAnimationDirectory == null) {
            Emote.LOGGER.warn("Bundled emote animations were not found.");
            return;
        }

        try (Stream<Path> paths = Files.walk(bundledAnimationDirectory)) {
            for (Path sourcePath : paths.filter(Files::isRegularFile).toList()) {
                Path relativePath = bundledAnimationDirectory.relativize(sourcePath);
                Path targetPath = getAnimationDirectory().resolve(relativePath.toString());
                Files.createDirectories(targetPath.getParent());
                Files.copy(sourcePath, targetPath);
            }
        }

        Emote.LOGGER.info("Installed bundled emote animations");
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
        Emote.LOGGER.info("Loaded main config from {}", CONFIG_FILE_NAME);
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
        Emote.LOGGER.info("Loaded emote access rules from {}", EMOTE_ACCESS_FILE_NAME);
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

    private String normalizeRequiredValue(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

}
