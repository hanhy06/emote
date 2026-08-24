package io.github.hanhy06.emote.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class PolymerResourcePackDistributor {
    private static final String AUTO_HOST_FILE_NAME = "auto-host.json";
    private static final String RESOURCE_PACK_FILE_NAME = "resource-pack.json";

    private final ConfigManager configManager;
    private final ResourcePackAssembler assembler;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this.configManager = configManager;
        this.assembler = new ResourcePackAssembler();

        configurePolymer();
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(this::addEmoteResources);
    }

    public void rebuildAndPush() {
        try {
            PolymerResourcePackUtils.getInstance().build(this.configManager.getGeneratedResourcePackPath());
            pushToOnlinePlayers();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EmoteMod.LOGGER.warn("Interrupted while building the emote resource pack");
        } catch (ExecutionException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception.getCause());
        }
    }

    private void addEmoteResources(ResourcePackBuilder builder) {
        try {
            this.configManager.configureResourcePack();
            int resourceCount = this.assembler.addTo(this.configManager.getResourcePackDirectory(), builder);
            EmoteMod.LOGGER.info("Added {} emote resources to the Polymer resource pack", resourceCount);
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to add emote resources to the Polymer resource pack: {}", exception.getMessage());
        }
    }

    private void pushToOnlinePlayers() {
        if (EmoteMod.SERVER == null) {
            return;
        }

        ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
        for (var player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
            var context = player.connection.getPacketContext();
            if (!provider.isReady(context)) {
                continue;
            }
            for (var pack : provider.getProperties(context)) {
                player.connection.send(new ClientboundResourcePackPushPacket(
                    pack.id(),
                    pack.url(),
                    pack.hash(),
                    pack.isRequired(),
                    Optional.ofNullable(pack.prompt())
                ));
            }
        }
    }

    private void configurePolymer() {
        Path polymerConfigDirectory = this.configManager.getGeneratedResourcePackPath().getParent().getParent().resolve("polymer");
        try {
            updateJson(polymerConfigDirectory.resolve(AUTO_HOST_FILE_NAME), json -> {
                json.addProperty("enabled", true);
                json.addProperty("type", "polymer:automatic");
                if (!json.has("settings")) {
                    json.add("settings", new JsonObject());
                }
            });
            updateJson(polymerConfigDirectory.resolve(RESOURCE_PACK_FILE_NAME), json ->
                json.addProperty("resource_pack_location", resourcePackLocation())
            );
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to configure Polymer AutoHost: {}", exception.getMessage());
        }
    }

    private String resourcePackLocation() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path resourcePack = this.configManager.getGeneratedResourcePackPath().toAbsolutePath().normalize();
        String location;
        try {
            location = gameDirectory.relativize(resourcePack).toString();
        } catch (IllegalArgumentException exception) {
            location = resourcePack.toString();
        }
        return location.replace('\\', '/');
    }

    private void updateJson(Path path, Consumer<JsonObject> update) throws IOException {
        JsonObject json = Files.isRegularFile(path)
            ? JsonParser.parseString(Files.readString(path)).getAsJsonObject()
            : new JsonObject();
        String previousJson = json.toString();
        update.accept(json);
        if (json.toString().equals(previousJson)) {
            return;
        }

        Files.createDirectories(path.getParent());
        Path temporaryFile = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporaryFile, this.gson.toJson(json));
            try {
                Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
