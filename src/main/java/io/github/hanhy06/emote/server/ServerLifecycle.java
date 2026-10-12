package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.application.PlaybackCooldownService;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.MarkerPlaybackManager;
import io.github.hanhy06.emote.playback.PlaybackHooks;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.playback.PlaybackScene;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.resource.ResourcePackService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ServerLifecycle {
    private final PlayerSkinManager playerSkinManager;
    private final PlaybackCooldownService cooldowns;
    private final EmoteCatalog emoteCatalog;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final MarkerPlaybackManager markerPlayback;
    private final ReloadService reloadService;
    private final WheelSyncService wheelSyncService;
    private final IdlePlaybackService idlePlaybackService;
    private final ResourcePackService resources;
    private final BooleanSupplier missingSkinUploadProvider;

    public ServerLifecycle(
        PlayerSkinManager playerSkinManager,
        PlaybackCooldownService cooldowns,
        EmoteCatalog emoteCatalog,
        PlayerPlaybackManager playerPlaybackManager,
        MarkerPlaybackManager markerPlayback,
        ReloadService reloadService,
        WheelSyncService wheelSyncService,
        IdlePlaybackService idlePlaybackService,
        ResourcePackService resources,
        BooleanSupplier missingSkinUploadProvider
    ) {
        this.playerSkinManager = playerSkinManager;
        this.cooldowns = cooldowns;
        this.emoteCatalog = emoteCatalog;
        this.playerPlaybackManager = playerPlaybackManager;
        this.markerPlayback = markerPlayback;
        this.reloadService = reloadService;
        this.wheelSyncService = wheelSyncService;
        this.idlePlaybackService = idlePlaybackService;
        this.resources = resources;
        this.missingSkinUploadProvider = missingSkinUploadProvider;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::handleServerStopped);
        ServerTickEvents.START_SERVER_TICK.register(ignoredServer -> {
            this.markerPlayback.tick();
            this.idlePlaybackService.tick();
            this.playerPlaybackManager.engine().beginTick();
        });
        ServerTickEvents.END_SERVER_TICK.register(ignoredServer -> this.playerPlaybackManager.engine().tick());
        PlaybackHooks.INTERRUPTION.register(this.playerPlaybackManager::interrupt);
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, ignoredSource, ignoredBaseDamage, damageTaken, ignoredBlocked) -> {
            if (damageTaken > 0.0F && entity instanceof ServerPlayer player) {
                this.playerPlaybackManager.interrupt(player, PlaybackStopReason.DAMAGED);
            }
        });
        AttackEntityCallback.EVENT.register((player, ignoredLevel, ignoredHand, ignoredEntity, ignoredHitResult) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                this.playerPlaybackManager.interrupt(serverPlayer, PlaybackStopReason.ATTACKED);
            }
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register(
            (handler, ignoredSender, ignoredServer) -> {
                this.wheelSyncService.syncPlayer(handler.player);
                this.playerSkinManager.checkPlayerSkin(handler.player);
                this.resources.notifyPlayer(handler.player);
                if (this.missingSkinUploadProvider.getAsBoolean()) {
                    handler.player.sendSystemMessage(Component.literal(
                        "[Emote] No bake accounts or MineSkin API key configured; only cached skin textures are available"
                    )
                        .withStyle(ChatFormatting.RED));
                }
            }
        );
        ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, ignoredServer) -> {
                if (EmoteMod.SERVER.isSameThread()) {
                    this.playerPlaybackManager.stop(handler.player, PlaybackStopReason.DISCONNECTED);
                    this.idlePlaybackService.removePlayer(handler.player);
                    this.playerSkinManager.removePlayer(handler.player.getUUID());
                } else {
                    EmoteMod.SERVER.execute(() -> {
                        this.playerPlaybackManager.stop(handler.player, PlaybackStopReason.DISCONNECTED);
                        this.idlePlaybackService.removePlayer(handler.player);
                        this.playerSkinManager.removePlayer(handler.player.getUUID());
                    });
                }
            }
        );
    }

    private void handleServerStarted(MinecraftServer server) {
        EmoteMod.SERVER = server;
        this.playerPlaybackManager.engine().startWorker();
        removeOrphanedRuntimeEntities(server);
        this.reloadService.reload();
    }

    private static void removeOrphanedRuntimeEntities(MinecraftServer server) {
        List<Entity> orphaned = new ArrayList<>();
        for (var level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.entityTags().contains(PlaybackScene.RUNTIME_TAG)) {
                    orphaned.add(entity);
                }
            }
        }
        orphaned.forEach(Entity::discard);
        if (!orphaned.isEmpty()) {
            EmoteMod.LOGGER.info("Removed {} orphaned emote runtime entities", orphaned.size());
        }
    }

    private void handleServerStopping(MinecraftServer ignoredServer) {
        this.playerPlaybackManager.engine().stopAll(PlaybackStopReason.SERVER_STOPPING);
        this.playerPlaybackManager.engine().stopWorker();
        this.cooldowns.clear();
        this.emoteCatalog.clearApiRegistrations();
        this.idlePlaybackService.clear();
        this.playerSkinManager.cancelPendingBakes();
    }

    private void handleServerStopped(MinecraftServer ignoredServer) {
        EmoteMod.SERVER = null;
    }
}
