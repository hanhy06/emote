package io.github.hanhy06.emote.resource;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
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
    private volatile int lastContributedResourceCount;

    public PolymerResourcePackDistributor(ConfigManager configManager) {
        this.configManager = configManager;
        this.contributor = new ResourcePackContributor();

        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(this::addEmoteResources);
    }

    public void rebuildAndPush() {
        try {
            if (!this.contributor.hasResources(this.configManager.getResourcePackDirectory())
                && this.lastContributedResourceCount == 0) {
                EmoteMod.LOGGER.info("Skipping Polymer resource pack rebuild because Emote contributes no resources");
                return;
            }
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect emote resource inputs", exception);
            return;
        }

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
            this.lastContributedResourceCount = resourceCount;
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
}
