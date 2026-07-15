package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.mixin.bridge.PlaybackHooks;
import io.github.hanhy06.emote.network.service.WheelSyncService;
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
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(ignoredServer -> this.playbackManager.tick());
        PlaybackHooks.INTERRUPTION.register(this.playbackManager::stopEmote);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, ignoredSource, ignoredBaseDamage, damageTaken, ignoredBlocked) -> {
            if (damageTaken > 0.0F && entity instanceof ServerPlayer player) {
                this.playbackManager.stopEmote(player);
            }
        });
        AttackEntityCallback.EVENT.register((player, ignoredLevel, ignoredHand, ignoredEntity, ignoredHitResult) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                this.playbackManager.stopEmote(serverPlayer);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register(
            (handler, ignoredSender, ignoredServer) -> this.wheelSyncService.syncPlayer(handler.player)
        );
        ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, ignoredServer) -> this.playbackManager.stopEmote(handler.player)
        );
    }

    private void handleServerStarted(MinecraftServer server) {
        Emote.SERVER = server;
        this.reloadService.loadOnServerStart();
    }

    private void handleServerStopping(MinecraftServer server) {
        Emote.SERVER = server;
        this.playbackManager.stopAllEmotes();
        this.skinManager.cancelPendingBakes();
        Emote.SERVER = null;
        Emote.LOGGER.info("stop emotes");
    }
}
