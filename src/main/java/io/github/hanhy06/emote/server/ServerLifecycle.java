package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackHooks;
import io.github.hanhy06.emote.playback.PlaybackManager;
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
    private final EmoteRegistry emoteRegistry;
    private final PlaybackManager playbackManager;
    private final ReloadService reloadService;
    private final WheelSyncService wheelSyncService;
    private final IdlePlaybackService idlePlaybackService;

    public ServerLifecycle(
        PlayerSkinManager playerSkinManager,
        EmoteRegistry emoteRegistry,
        PlaybackManager playbackManager,
        ReloadService reloadService,
        WheelSyncService wheelSyncService,
        IdlePlaybackService idlePlaybackService
    ) {
        this.playerSkinManager = playerSkinManager;
        this.emoteRegistry = emoteRegistry;
        this.playbackManager = playbackManager;
        this.reloadService = reloadService;
        this.wheelSyncService = wheelSyncService;
        this.idlePlaybackService = idlePlaybackService;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(ignoredServer -> {
            this.playbackManager.tick();
            this.idlePlaybackService.tick();
        });
        PlaybackHooks.INTERRUPTION.register(this.playbackManager::interrupt);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, ignoredSource, ignoredBaseDamage, damageTaken, ignoredBlocked) -> {
            if (damageTaken > 0.0F && entity instanceof ServerPlayer player) {
                this.playbackManager.interrupt(player, PlaybackStopReason.DAMAGED);
            }
        });
        AttackEntityCallback.EVENT.register((player, ignoredLevel, ignoredHand, ignoredEntity, ignoredHitResult) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                this.playbackManager.interrupt(serverPlayer, PlaybackStopReason.ATTACKED);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register(
            (handler, ignoredSender, ignoredServer) -> this.wheelSyncService.syncPlayer(handler.player)
        );
        ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, ignoredServer) -> {
                if (Emote.SERVER.isSameThread()) {
                    this.playbackManager.stop(handler.player, PlaybackStopReason.DISCONNECTED);
                    this.idlePlaybackService.removePlayer(handler.player);
                } else {
                    Emote.SERVER.execute(() -> {
                        this.playbackManager.stop(handler.player, PlaybackStopReason.DISCONNECTED);
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
        this.playbackManager.stopAll(PlaybackStopReason.SERVER_STOPPING);
        int removedApiEmotes = this.emoteRegistry.clearApiRegistrations();
        this.idlePlaybackService.clear();
        this.playerSkinManager.cancelPendingBakes();
        Emote.SERVER = null;
        Emote.LOGGER.info("stop emotes, cleared API emotes={}", removedApiEmotes);
    }
}
