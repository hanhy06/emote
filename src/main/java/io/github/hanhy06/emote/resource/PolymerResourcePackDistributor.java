package io.github.hanhy06.emote.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.config.JsonFileStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class PolymerResourcePackDistributor {
    private static final String AUTO_HOST_FILE_NAME = "auto-host.json";

    private final ConfigManager configManager;
    private final ResourcePackContributor contributor;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this.configManager = configManager;
        this.contributor = new ResourcePackContributor();

        configureAutoHost();
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(this::addEmoteResources);
    }

    public void rebuildAndPush() {
        try {
            PolymerResourcePackUtils.getInstance().build(PolymerResourcePackUtils.getMainPath());
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
            int resourceCount = this.contributor.addTo(this.configManager.getResourcePackDirectory(), builder);
            EmoteMod.LOGGER.info("Added {} emote resources to the Polymer resource pack", resourceCount);
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to add emote resources to the Polymer resource pack", exception);
        }
    }

    private void pushToOnlinePlayers() {
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

    private void configureAutoHost() {
        Path polymerConfigDirectory = FabricLoader.getInstance().getConfigDir().resolve("polymer");
        try {
            updateJson(polymerConfigDirectory.resolve(AUTO_HOST_FILE_NAME), json -> {
                json.addProperty("enabled", true);
                json.addProperty("type", "polymer:automatic");
                if (!json.has("settings")) {
                    json.add("settings", new JsonObject());
                }
            });
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to configure Polymer AutoHost", exception);
        }
    }

    private void updateJson(Path path, Consumer<JsonObject> update) throws IOException {
        JsonObject json = Files.isRegularFile(path)
            ? JsonFileStore.readObject(path)
            : new JsonObject();
        if (json == null) {
            throw new IOException("Expected a JSON object in " + path);
        }

        JsonObject previousJson = json.deepCopy();
        update.accept(json);
        if (json.equals(previousJson)) {
            return;
        }

        JsonFileStore.writeObjectAtomically(path, json, this.gson);
    }
}
