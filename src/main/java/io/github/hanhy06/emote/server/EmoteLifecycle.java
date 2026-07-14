package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

public class EmoteLifecycle {
    private final PlayerSkinManager skinManager;
    private final PlaybackManager playbackManager;
    private final EmoteReloadService reloadService;
    private final WheelSyncService wheelSyncService;

    public EmoteLifecycle(
        PlayerSkinManager skinManager,
        PlaybackManager playbackManager,
        EmoteReloadService reloadService,
        WheelSyncService wheelSyncService
    ) {
        this.skinManager = skinManager;
        this.playbackManager = playbackManager;
        this.reloadService = reloadService;
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
        this.reloadService.handleServerStarted();
    }

    private void handleDataPackReloadStart(MinecraftServer ignoredServer) {
        this.reloadService.handleDataPackReloadStart();
    }

    private void handleDataPackReload(MinecraftServer ignoredServer, boolean success) {
        this.reloadService.handleDataPackReloadEnd(success);
    }

    private void handleServerStopping(MinecraftServer server) {
        Emote.SERVER = server;
        this.playbackManager.stopAllEmotes();
        this.skinManager.clear();
        Emote.SERVER = null;
        Emote.LOGGER.info("stop emotes");
    }
}
