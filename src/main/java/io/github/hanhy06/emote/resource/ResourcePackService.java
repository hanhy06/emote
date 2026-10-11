package io.github.hanhy06.emote.resource;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

public final class ResourcePackService {
    private static final String MISSING_POLYMER_MESSAGE = "[Emote] Resource files were found, but Polymer Resource Pack and Auto Host are not installed. Automatic resource pack delivery is unavailable. Install Polymer and restart the server.";

    private final ConfigManager configManager;
    private final ResourcePackContributor contributor = new ResourcePackContributor();
    private final @Nullable PolymerResourcePackDistributor distributor;
    private boolean missingPolymer;

    public ResourcePackService(ConfigManager configManager) {
        this.configManager = configManager;
        FabricLoader loader = FabricLoader.getInstance();
        this.distributor = loader.isModLoaded("polymer-resource-pack") && loader.isModLoaded("polymer-autohost")
            ? new PolymerResourcePackDistributor()
            : null;
    }

    public BuildResult rebuild() {
        ResourcePackContributor.Snapshot snapshot;
        try {
            this.configManager.configureResourcePack();
            snapshot = this.contributor.read(this.configManager.getResourcePackDirectory());
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to inspect emote resource inputs", exception);
            return BuildResult.FAILED;
        }

        if (this.distributor == null) {
            this.missingPolymer = !snapshot.isEmpty();
            return BuildResult.UNCHANGED;
        }
        return this.distributor.rebuild(snapshot);
    }

    public void pushToOnlinePlayers() {
        if (this.distributor != null) {
            this.distributor.pushToOnlinePlayers();
        }
    }

    public void notifyOnlinePlayers() {
        if (this.missingPolymer) {
            EmoteMod.LOGGER.error(MISSING_POLYMER_MESSAGE);
            for (ServerPlayer player : EmoteMod.SERVER.getPlayerList().getPlayers()) {
                notifyPlayer(player);
            }
        }
    }

    public void notifyPlayer(ServerPlayer player) {
        if (this.missingPolymer) {
            player.sendSystemMessage(Component.literal(MISSING_POLYMER_MESSAGE).withStyle(ChatFormatting.RED));
        }
    }

    public enum BuildResult {
        UNCHANGED,
        BUILT,
        FAILED
    }
}
