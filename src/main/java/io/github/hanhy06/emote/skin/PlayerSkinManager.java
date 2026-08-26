package io.github.hanhy06.emote.skin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.mineskin.MineSkinTaskQueue;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlayerSkinManager implements ConfigListener {
    private final PlayerSkinProvider provider;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();

    public PlayerSkinManager() {
        this(
            new MineSkinProvider(
                new PlayerSkinBaker(),
                new MineSkinCache(),
                new MineSkinClient(),
                new MineSkinTaskQueue()
            ),
            PlayerSkinManager::readPlayerSkinSource
        );
    }

    PlayerSkinManager(
        PlayerSkinProvider provider,
        Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
        this.provider.setListener(new PlayerSkinProvider.Listener() {
            @Override
            public void onReady(UUID playerUuid) {
                notifySkinReady(playerUuid);
            }

            @Override
            public void onFailed(UUID playerUuid) {
                notifySkinFailed(playerUuid);
            }
        });
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.provider.onConfigReload(newConfig);
    }

    public PlayerSkinPreparation preparePlayerSkin(ServerPlayer player, List<SkinBinding> skinBindings) {
        if (skinBindings.isEmpty()) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }
        Set<PlayerSkinRegion> requiredTextureKeys = new LinkedHashSet<>(skinBindings.size());
        for (SkinBinding binding : skinBindings) {
            requiredTextureKeys.add(binding.region());
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0);
        }
        return this.provider.prepare(skinSource, requiredTextureKeys);
    }

    public void addReadyListener(Consumer<UUID> readyListener) {
        this.readyListeners.add(Objects.requireNonNull(readyListener, "readyListener"));
    }

    public void cancelPendingBakes() {
        this.provider.cancelPendingBakes();
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = EmoteMod.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Your skin is ready. Play the emote again."));
            }
            for (Consumer<UUID> readyListener : this.readyListeners) {
                readyListener.accept(playerUuid);
            }
        });
    }

    private void notifySkinFailed(UUID playerUuid) {
        MinecraftServer server = EmoteMod.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("We could not prepare your skin. Try again later."));
            }
        });
    }

    private static PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        MinecraftServer server = EmoteMod.SERVER;
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
