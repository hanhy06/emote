package io.github.hanhy06.emote.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.EmoteMod;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class ConfigManager {
    private static final String CONFIG_FILE_DIR = EmoteMod.MOD_ID;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String ACCESS_CONFIG_FILE_NAME = "emotes.json";
    private static final String EMOTE_DIRECTORY_NAME = "emote";
    private static final String RESOURCE_PACK_DIRECTORY_NAME = "resource-pack";
    private static final String GENERATED_DIRECTORY_NAME = "generated";
    private static final String GENERATED_RESOURCE_PACK_FILE_NAME = "emote-resource-pack.zip";
    private static final String RESOURCE_PACK_METADATA_FILE_NAME = "pack.mcmeta";
    private static final String BUNDLED_EMOTE_DIRECTORY_NAME = "default-emotes";

    private final Path configDirPath;
    private final @Nullable Path bundledEmoteDirectory;
    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private final ConfigJsonCodec jsonCodec = new ConfigJsonCodec();
    private final List<ConfigListener> listeners = new ArrayList<>();
    private final List<AccessConfigListener> accessConfigListeners = new ArrayList<>();

    private Config config = Config.createDefault();
    private AccessConfig accessConfig = AccessConfig.createDefault();

    public ConfigManager(Path configBasePath) {
        this(configBasePath, findBundledEmoteDirectory().orElse(null));
    }

    ConfigManager(Path configBasePath, @Nullable Path bundledEmoteDirectory) {
        this.configDirPath = configBasePath.resolve(CONFIG_FILE_DIR);
        this.bundledEmoteDirectory = bundledEmoteDirectory;
    }

    public void configure() {
        boolean installBundledEmotes = Files.notExists(this.configDirPath);

        try {
            Files.createDirectories(this.configDirPath);
            Files.createDirectories(getEmoteDirectory());
            Files.createDirectories(getResourcePackDirectory());
            Files.createDirectories(getGeneratedDirectory());
            writeResourcePackMetadataIfAbsent();
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to create config files. Using default settings.", exception);
            return;
        }

        if (installBundledEmotes) {
            try {
                installBundledEmotes(this.bundledEmoteDirectory);
            } catch (IOException exception) {
                EmoteMod.LOGGER.warn("Failed to install bundled emotes.", exception);
            }
        }

        writeIfAbsent(CONFIG_FILE_NAME, this.jsonCodec.writeConfig(this.config));
        writeIfAbsent(ACCESS_CONFIG_FILE_NAME, this.jsonCodec.writeAccessConfig(this.accessConfig));
    }

    private static Optional<Path> findBundledEmoteDirectory() {
        return FabricLoader.getInstance()
            .getModContainer(EmoteMod.MOD_ID)
            .flatMap(container -> container.findPath(BUNDLED_EMOTE_DIRECTORY_NAME));
    }

    private void installBundledEmotes(@Nullable Path bundledEmoteDirectory) throws IOException {
        if (bundledEmoteDirectory == null) {
            EmoteMod.LOGGER.warn("Bundled emotes were not found.");
            return;
        }

        try (Stream<Path> paths = Files.walk(bundledEmoteDirectory)) {
            for (Path sourcePath : paths.filter(Files::isRegularFile).toList()) {
                Path relativePath = bundledEmoteDirectory.relativize(sourcePath);
                Path targetPath = getEmoteDirectory().resolve(relativePath.toString());
                Files.createDirectories(targetPath.getParent());
                Files.copy(sourcePath, targetPath);
            }
        }

        EmoteMod.LOGGER.info("Installed bundled emotes");
    }

    public Config getConfig() {
        return this.config;
    }

    public AccessConfig getAccessConfig() {
        return this.accessConfig;
    }

    public Path getEmoteDirectory() {
        return this.configDirPath.resolve(EMOTE_DIRECTORY_NAME);
    }

    public Path getResourcePackDirectory() {
        return this.configDirPath.resolve(RESOURCE_PACK_DIRECTORY_NAME);
    }

    public Path getGeneratedResourcePackPath() {
        return getGeneratedDirectory().resolve(GENERATED_RESOURCE_PACK_FILE_NAME);
    }

    private Path getGeneratedDirectory() {
        return this.configDirPath.resolve(GENERATED_DIRECTORY_NAME);
    }

    private void writeResourcePackMetadataIfAbsent() throws IOException {
        Path metadataPath = getResourcePackDirectory().resolve(RESOURCE_PACK_METADATA_FILE_NAME);
        if (Files.exists(metadataPath)) {
            return;
        }

        JsonArray format = new JsonArray();
        format.add(88);
        format.add(0);
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Emote resources");
        pack.add("min_format", format.deepCopy());
        pack.add("max_format", format);
        JsonObject metadata = new JsonObject();
        metadata.add("pack", pack);
        JsonFileStore.writeObjectAtomically(metadataPath, metadata, this.gson);
        EmoteMod.LOGGER.info("Saved {}/{}", RESOURCE_PACK_DIRECTORY_NAME, RESOURCE_PACK_METADATA_FILE_NAME);
    }

    public boolean readConfig() {
        JsonObject configJson = readJsonFile(CONFIG_FILE_NAME);
        Config loadedConfig;
        try {
            loadedConfig = this.jsonCodec.readConfig(configJson);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Config contains invalid field values. Keeping current config.", exception);
            broadcastConfig();
            return false;
        }
        if (loadedConfig == null) {
            EmoteMod.LOGGER.warn("Config is empty or invalid. Keeping current config.");
            broadcastConfig();
            return false;
        }

        this.config = loadedConfig;
        broadcastConfig();
        EmoteMod.LOGGER.info("Loaded main config from {}", CONFIG_FILE_NAME);
        return true;
    }

    public boolean readAccessConfig() {
        JsonObject configJson = readJsonFile(ACCESS_CONFIG_FILE_NAME);
        AccessConfig loadedConfig;
        try {
            loadedConfig = this.jsonCodec.readAccessConfig(configJson);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Emote access config contains invalid field values. Keeping current config.", exception);
            broadcastAccessConfig();
            return false;
        }

        if (loadedConfig == null) {
            EmoteMod.LOGGER.warn("Emote access config is empty or invalid. Keeping current config.");
            broadcastAccessConfig();
            return false;
        }

        this.accessConfig = loadedConfig;
        broadcastAccessConfig();
        EmoteMod.LOGGER.info("Loaded emote access rules from {}", ACCESS_CONFIG_FILE_NAME);
        return true;
    }

    public void addListener(ConfigListener listener) {
        this.listeners.add(listener);
    }

    public void addAccessConfigListener(AccessConfigListener listener) {
        this.accessConfigListeners.add(listener);
    }

    public boolean setEmoteEnabled(String id, boolean enabled) {
        String normalizedId = ConfigJsonCodec.normalizeRequiredValue(id);
        if (normalizedId == null) {
            throw new IllegalArgumentException("emote id must not be blank");
        }

        List<String> nextDisabled = new ArrayList<>(this.accessConfig.disabled());
        if (enabled) {
            nextDisabled.remove(normalizedId);
        } else if (!nextDisabled.contains(normalizedId)) {
            nextDisabled.add(normalizedId);
        }
        AccessConfig nextConfig = new AccessConfig(
            nextDisabled,
            this.accessConfig.permissions()
        );

        if (!writeJsonFile(ACCESS_CONFIG_FILE_NAME, this.jsonCodec.writeAccessConfig(nextConfig))) {
            return false;
        }

        this.accessConfig = nextConfig;
        broadcastAccessConfig();
        return true;
    }

    private void broadcastConfig() {
        for (ConfigListener listener : this.listeners) {
            listener.onConfigReload(this.config);
        }
    }

    private void broadcastAccessConfig() {
        for (AccessConfigListener listener : this.accessConfigListeners) {
            listener.onAccessConfigReload(this.accessConfig);
        }
    }

    private JsonObject readJsonFile(String fileName) {
        Path filePath = this.configDirPath.resolve(fileName);
        try {
            return JsonFileStore.readObject(filePath);
        } catch (IOException | RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to read {}: {}", fileName, exception.getMessage());
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
            EmoteMod.LOGGER.info("Saved {}", fileName);
            return true;
        } catch (IOException exception) {
            EmoteMod.LOGGER.error("Failed to write {}: {}", fileName, exception.getMessage());
            return false;
        }
    }
}
