package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.application.PlaybackPolicyService;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlaybackHooks;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public class ServerLifecycle {
    private final PlayerSkinManager playerSkinManager;
    private final PlaybackPolicyService playbackPolicy;
    private final EmoteCatalog emoteCatalog;
    private final PlaybackEngine playbackEngine;
    private final ReloadService reloadService;
    private final WheelSyncService wheelSyncService;
    private final IdlePlaybackService idlePlaybackService;

    public ServerLifecycle(
        PlayerSkinManager playerSkinManager,
        PlaybackPolicyService playbackPolicy,
        EmoteCatalog emoteCatalog,
        PlaybackEngine playbackEngine,
        ReloadService reloadService,
        WheelSyncService wheelSyncService,
        IdlePlaybackService idlePlaybackService
    ) {
        this.playerSkinManager = playerSkinManager;
        this.playbackPolicy = playbackPolicy;
        this.emoteCatalog = emoteCatalog;
        this.playbackEngine = playbackEngine;
        this.reloadService = reloadService;
        this.wheelSyncService = wheelSyncService;
        this.idlePlaybackService = idlePlaybackService;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::handleServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(ignoredServer -> {
            this.playbackEngine.tick();
            this.idlePlaybackService.tick();
        });
        PlaybackHooks.INTERRUPTION.register(this.playbackEngine::interrupt);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, ignoredSource, ignoredBaseDamage, damageTaken, ignoredBlocked) -> {
            if (damageTaken > 0.0F && entity instanceof ServerPlayer player) {
                this.playbackEngine.interrupt(player, PlaybackStopReason.DAMAGED);
            }
        });
        AttackEntityCallback.EVENT.register((player, ignoredLevel, ignoredHand, ignoredEntity, ignoredHitResult) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                this.playbackEngine.interrupt(serverPlayer, PlaybackStopReason.ATTACKED);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register(
            (handler, ignoredSender, ignoredServer) -> this.wheelSyncService.syncPlayer(handler.player)
        );
        ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, ignoredServer) -> {
                if (Emote.SERVER.isSameThread()) {
                    this.playbackEngine.stop(handler.player, PlaybackStopReason.DISCONNECTED);
                    this.idlePlaybackService.removePlayer(handler.player);
                } else {
                    Emote.SERVER.execute(() -> {
                        this.playbackEngine.stop(handler.player, PlaybackStopReason.DISCONNECTED);
                        this.idlePlaybackService.removePlayer(handler.player);
                    });
                }
            }
        );
    }

    private void handleServerStarted(MinecraftServer server) {
        Emote.SERVER = server;
        this.reloadService.loadOnServerStart();
    }

    private void handleServerStopping(MinecraftServer ignoredServer) {
        this.playbackEngine.stopAll(PlaybackStopReason.SERVER_STOPPING);
        this.playbackPolicy.clearCooldowns();
        int removedApiEmotes = this.emoteCatalog.clearApiRegistrations();
        this.idlePlaybackService.clear();
        this.playerSkinManager.cancelPendingBakes();
        Emote.LOGGER.info("stop emotes, cleared API emotes={}", removedApiEmotes);
    }

    private void handleServerStopped(MinecraftServer ignoredServer) {
        Emote.SERVER = null;
    }
}
