package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public final class PolymerResourcePackDistributor {
    private final ConfigManager configManager;
    private final ResourcePackContributor contributor;
    private volatile ResourcePackContributor.Snapshot lastBuiltSnapshot;
    private volatile ResourcePackContributor.Snapshot preparedSnapshot;
    private volatile ResourcePackContributor.Snapshot buildingSnapshot;

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this.configManager = configManager;
        this.contributor = new ResourcePackContributor();

        PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(() -> this.buildingSnapshot = null);
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(this::addEmoteResources);
        PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(this::finishResourcePackBuild);
    }

    public void rebuildAndPush() {
        ResourcePackContributor.Snapshot snapshot;
        try {
            snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
            ResourcePackContributor.Snapshot lastBuilt = this.lastBuiltSnapshot;
            if ((lastBuilt == null && snapshot.isEmpty())
                || (lastBuilt != null && snapshot.hasSameContent(lastBuilt))) {
                EmoteMod.LOGGER.info("Skipping Polymer resource pack rebuild because emote resources are unchanged");
                return;
            }
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect emote resource inputs", exception);
            return;
        }

        try {
            this.preparedSnapshot = snapshot;
            OutputGenerator.Result result = PolymerResourcePackUtils.getInstance().build(PolymerResourcePackUtils.getMainPath());
            if (result == null || !snapshot.hasSameContent(this.lastBuiltSnapshot)) {
                EmoteMod.LOGGER.warn("Failed to complete the emote resource pack build");
                return;
            }
            pushToOnlinePlayers();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EmoteMod.LOGGER.warn("Interrupted while building the emote resource pack");
        } catch (ExecutionException exception) {
            EmoteMod.LOGGER.warn("Failed to build the emote resource pack", exception.getCause());
        } finally {
            this.preparedSnapshot = null;
        }
    }

    private void addEmoteResources(ResourcePackBuilder builder) {
        try {
            this.configManager.configureResourcePack();
            ResourcePackContributor.Snapshot snapshot = this.preparedSnapshot;
            if (snapshot == null) {
                snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
            }
            int resourceCount = this.contributor.addTo(snapshot, builder);
            this.buildingSnapshot = snapshot;
            EmoteMod.LOGGER.info("Added {} emote resources to the Polymer resource pack", resourceCount);
        } catch (IOException exception) {
            this.buildingSnapshot = null;
            EmoteMod.LOGGER.warn("Failed to add emote resources to the Polymer resource pack", exception);
        }
    }

    private void finishResourcePackBuild(Object output) {
        ResourcePackContributor.Snapshot snapshot = this.buildingSnapshot;
        this.buildingSnapshot = null;
        if (output instanceof OutputGenerator.Result && snapshot != null) {
            this.lastBuiltSnapshot = snapshot;
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
}
