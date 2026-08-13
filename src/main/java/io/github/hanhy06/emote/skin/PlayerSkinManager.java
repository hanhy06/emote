package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinPipeline;
import io.github.hanhy06.emote.skin.mineskin.MineSkinTaskQueue;
import io.github.hanhy06.emote.skin.mineskin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlayerSkinManager implements ConfigListener {
    private final MineSkinPipeline mineSkinManager;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();

    public PlayerSkinManager() {
        this(
            new PlayerSkinBaker(),
            new MineSkinCache(),
            new MineSkinClient(),
            new MineSkinTaskQueue(),
            PlayerSkinManager::readPlayerSkinSource
        );
    }

    PlayerSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache mineSkinCache,
        MineSkinClient mineSkinClient,
        MineSkinTaskQueue generationQueue,
        Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver
    ) {
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
        this.mineSkinManager = new MineSkinPipeline(
            Objects.requireNonNull(playerSkinBaker, "playerSkinBaker"),
            Objects.requireNonNull(mineSkinCache, "mineSkinCache"),
            Objects.requireNonNull(mineSkinClient, "mineSkinClient"),
            Objects.requireNonNull(generationQueue, "generationQueue"),
            this::notifySkinReady,
            this::notifySkinFailed
        );
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.mineSkinManager.configure(
            newConfig.mineSkinApiKey(),
            newConfig.mineSkinPollIntervalSeconds(),
            newConfig.mineSkinCacheRetentionDays(),
            newConfig.mineSkinCacheMaxMiB()
        );
    }

    public PlayerSkinPreparation preparePlayerSkin(ServerPlayer player, List<SkinBinding> skinParts) {
        if (skinParts.isEmpty()) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }
        Set<PlayerSkinRegion> requiredTextureKeys = new LinkedHashSet<>(skinParts.size());
        for (SkinBinding skinPart : skinParts) {
            requiredTextureKeys.add(skinPart.region());
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0);
        }
        return this.mineSkinManager.prepare(skinSource, requiredTextureKeys);
    }

    public void addReadyListener(Consumer<UUID> readyListener) {
        this.readyListeners.add(Objects.requireNonNull(readyListener, "readyListener"));
    }

    public void cancelPendingBakes() {
        this.mineSkinManager.cancelPendingBakes();
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Player skin is ready. Run the emote again."));
            }
            for (Consumer<UUID> readyListener : this.readyListeners) {
                readyListener.accept(playerUuid);
            }
        });
    }

    private void notifySkinFailed(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Player skin preparation failed. Try again later."));
            }
        });
    }

    private static PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        MinecraftServer server = Emote.SERVER;
        Property packedTextures = server.services().sessionService().getPackedTextures(player.getGameProfile());
        if (packedTextures == null) {
            return null;
        }
        MinecraftProfileTextures textures = server.services().sessionService().unpackTextures(packedTextures);
        MinecraftProfileTexture skinTexture = textures.skin();
        if (skinTexture == null) {
            return null;
        }
        boolean slimModel = "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
        return new PlayerSkinSource(
            player.getUUID(),
            player.getGameProfile().name(),
            skinTexture.getHash(),
            skinTexture.getUrl(),
            slimModel
        );
    }

}
