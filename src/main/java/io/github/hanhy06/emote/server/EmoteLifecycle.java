package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

public class EmoteLifecycle {
    private final ConfigManager configManager;
    private final PlayerSkinManager skinManager;
    private final PlaybackManager playbackManager;
    private final BDEngineDatapackProcessor bdEngineDatapackProcessor;
    private final WheelSyncService wheelSyncService;

    public EmoteLifecycle(
            ConfigManager configManager,
            PlayerSkinManager skinManager,
            PlaybackManager playbackManager,
            BDEngineDatapackProcessor bdEngineDatapackProcessor,
            WheelSyncService wheelSyncService
    ) {
        this.configManager = configManager;
        this.skinManager = skinManager;
        this.playbackManager = playbackManager;
        this.bdEngineDatapackProcessor = bdEngineDatapackProcessor;
        this.wheelSyncService = wheelSyncService;
    }

    public void register() {
        registerLifecycleCallbacks();
        registerPlaybackCallbacks();
        registerConnectionCallbacks();
    }

    private void registerLifecycleCallbacks() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, ignoredResourceManager) -> handleDataPackReloadStart(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, ignoredResourceManager, success) -> handleDataPackReload(server, success));
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
    }

    private void registerPlaybackCallbacks() {
        ServerTickEvents.END_SERVER_TICK.register(ignoredServer -> this.playbackManager.tick());
    }

    private void registerConnectionCallbacks() {
        ServerPlayConnectionEvents.JOIN.register((handler, ignoredSender, ignoredServer) -> syncJoinedPlayer(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignoredServer) -> stopDisconnectedPlayer(handler.player));
    }

    private void syncJoinedPlayer(net.minecraft.server.level.ServerPlayer player) {
        this.wheelSyncService.syncPlayer(player);
    }

    private void stopDisconnectedPlayer(net.minecraft.server.level.ServerPlayer player) {
        this.playbackManager.stopEmote(player);
    }

    private void handleServerStarted(MinecraftServer server) {
        Emote.SERVER = server;
        this.skinManager.reloadHttpServer();

        boolean reloadedResources = this.bdEngineDatapackProcessor.enableEmoteDatapacks();
        if (reloadedResources) {
            return;
        }

        int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes();
        Emote.LOGGER.info("emotes={}", emoteCount);
    }

    private void handleDataPackReloadStart(MinecraftServer server) {
        Emote.SERVER = server;
        this.configManager.readConfig();
        this.configManager.readIdentifierConfig();
        this.skinManager.reloadHttpServer();
    }

    private void handleDataPackReload(MinecraftServer server, boolean success) {
        Emote.SERVER = server;
        if (!success) {
            Emote.LOGGER.warn("Datapack reload failed");
            return;
        }

        this.playbackManager.stopAllEmotes();
        int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes();
        this.wheelSyncService.syncAll();
        Emote.LOGGER.info("reload emotes={}", emoteCount);
    }

    private void handleServerStopping(MinecraftServer server) {
        Emote.SERVER = server;
        this.playbackManager.stopAllEmotes();
        this.skinManager.clear();
        Emote.SERVER = null;
        Emote.LOGGER.info("stop emotes");
    }
}
