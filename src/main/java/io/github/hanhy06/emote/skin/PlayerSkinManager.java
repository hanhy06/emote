package io.github.hanhy06.emote.skin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
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
    private final Map<UUID, SkinIdentity> connectedSkins = new HashMap<>();
    private Set<PlayerSkinRegion> modelRegions = Set.of();

    public PlayerSkinManager(PlayerSkinProvider provider) {
        this(provider, PlayerSkinManager::readPlayerSkinSource);
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
        Set<PlayerSkinRegion> requiredTextureKeys = new LinkedHashSet<>(this.modelRegions);
        for (SkinBinding binding : skinBindings) {
            requiredTextureKeys.add(binding.region());
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0);
        }
        return this.provider.prepare(skinSource, requiredTextureKeys);
    }

    public void setModelBindings(Collection<SkinBinding> bindings) {
        Set<PlayerSkinRegion> regions = new LinkedHashSet<>();
        for (SkinBinding binding : bindings) {
            regions.add(binding.region());
        }
        this.modelRegions = Set.copyOf(regions);
    }

    public void checkPlayerSkin(ServerPlayer player) {
        PlayerSkinSource source = this.playerSkinSourceResolver.apply(player);
        if (source == null || this.modelRegions.isEmpty()) {
            return;
        }
        SkinIdentity identity = new SkinIdentity(source.textureHash(), source.slimModel());
        SkinIdentity previous = this.connectedSkins.put(source.playerUuid(), identity);
        if (identity.equals(previous)) {
            return;
        }
        PlayerSkinPreparation preparation = this.provider.prepare(source, this.modelRegions);
        if (previous != null && preparation.state() == PlayerSkinPreparation.State.READY) {
            for (Consumer<UUID> readyListener : this.readyListeners) {
                readyListener.accept(source.playerUuid());
            }
        }
    }

    public void removePlayer(UUID playerUuid) {
        this.connectedSkins.remove(playerUuid);
    }

    public void addReadyListener(Consumer<UUID> readyListener) {
        this.readyListeners.add(Objects.requireNonNull(readyListener, "readyListener"));
    }

    public void cancelPendingBakes() {
        this.connectedSkins.clear();
        this.provider.cancelPendingBakes();
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = EmoteMod.SERVER;
        server.execute(() -> {
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

    private record SkinIdentity(String textureHash, boolean slimModel) {
    }

}
