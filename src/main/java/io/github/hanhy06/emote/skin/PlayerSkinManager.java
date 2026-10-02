package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlayerSkinManager implements ConfigListener {
    private final PlayerSkinProvider provider;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final Function<String, CompletableFuture<PlayerSkinSource>> namedSkinSourceResolver;
    private final Map<String, CompletableFuture<PlayerSkinSource>> namedSources = new HashMap<>();
    private final List<Runnable> defaultReadyListeners = new CopyOnWriteArrayList<>();
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
        this(provider, playerSkinSourceResolver, PlayerSkinManager::resolveNamedSkinSource);
    }

    PlayerSkinManager(PlayerSkinProvider provider, Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver,
                      Function<String, CompletableFuture<PlayerSkinSource>> namedSkinSourceResolver) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
        this.namedSkinSourceResolver = Objects.requireNonNull(namedSkinSourceResolver, "namedSkinSourceResolver");
        this.provider.setListener(new PlayerSkinProvider.Listener() {
            @Override
            public void onDefaultReady() {
                EmoteMod.SERVER.execute(() -> {
                    EmoteMod.SERVER.getPlayerList().getPlayers().forEach(player -> {
                        for (Consumer<UUID> readyListener : readyListeners) readyListener.accept(player.getUUID());
                    });
                    defaultReadyListeners.forEach(Runnable::run);
                });
            }
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
        this.namedSources.clear();
        this.provider.onConfigReload(newConfig);
    }

    public PlayerSkinPreparation prepareNamedSkin(String name, List<SkinBinding> skinBindings) {
        if (skinBindings.isEmpty() || name.isBlank()) return prepareSkinSource(null, skinBindings);
        String key = name.strip().toLowerCase(Locale.ROOT);
        CompletableFuture<PlayerSkinSource> future = this.namedSources.computeIfAbsent(key, ignored ->
            this.namedSkinSourceResolver.apply(name.strip()).exceptionally(exception -> {
                EmoteMod.LOGGER.warn("Could not resolve skin for {}; using any prepared default skin", name);
                return null;
            }));
        if (!future.isDone()) return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.PREPARING, 0);
        return prepareSkinSource(future.getNow(null), skinBindings);
    }

    private static CompletableFuture<PlayerSkinSource> resolveNamedSkinSource(String name) {
        MinecraftServer server = EmoteMod.SERVER;
        return CompletableFuture.supplyAsync(() -> server.services().profileResolver().fetchByName(name)
            .map(profile -> PlayerSkinSource.fromProfile(profile, server.services().sessionService())).orElse(null), Util.nonCriticalIoPool())
            .orTimeout(15, TimeUnit.SECONDS);
    }

    public PlayerSkinPreparation preparePlayerSkin(ServerPlayer player, List<SkinBinding> skinBindings) {
        return prepareSkinSource(skinBindings.isEmpty() ? null : resolvePlayerSkinSource(player), skinBindings);
    }

    public PlayerSkinPreparation prepareSkinSource(PlayerSkinSource skinSource, List<SkinBinding> skinBindings) {
        if (skinBindings.isEmpty()) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }
        Set<PlayerSkinRegion> requiredTextureKeys = new LinkedHashSet<>(this.modelRegions);
        for (SkinBinding binding : skinBindings) {
            requiredTextureKeys.add(binding.region());
        }
        PlayerSkinPreparation preparation = skinSource == null
            ? new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0)
            : this.provider.prepare(skinSource, requiredTextureKeys);
        return withDefaultSkin(preparation, requiredTextureKeys);
    }

    PlayerSkinPreparation withDefaultSkin(PlayerSkinPreparation preparation, Set<PlayerSkinRegion> regions) {
        var fallback = this.provider.defaultSkin();
        if (fallback == null || preparation.state() == PlayerSkinPreparation.State.READY || preparation.preparing()) return preparation;
        Map<PlayerSkinRegion, String> merged = new HashMap<>();
        for (PlayerSkinRegion region : regions) {
            String texture = fallback.findTextureUrl(region);
            if (texture != null) merged.put(region, texture);
        }
        if (preparation.preparedPlayerSkin() != null) merged.putAll(preparation.preparedPlayerSkin().textureUrlMap());
        return new PlayerSkinPreparation(merged.isEmpty() ? null : new PreparedPlayerSkin(merged),
            preparation.state(), preparation.progressPercent());
    }

    public void setModelBindings(Collection<SkinBinding> bindings) {
        Set<PlayerSkinRegion> regions = new LinkedHashSet<>();
        for (SkinBinding binding : bindings) {
            regions.add(binding.region());
        }
        this.modelRegions = Set.copyOf(regions);
        this.provider.setDefaultRegions(this.modelRegions);
    }

    public void checkPlayerSkin(ServerPlayer player) {
        PlayerSkinSource source = resolvePlayerSkinSource(player);
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

    public void addDefaultReadyListener(Runnable listener) {
        this.defaultReadyListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void cancelPendingBakes() {
        this.namedSources.clear();
        this.connectedSkins.clear();
        this.provider.cancelPendingBakes();
    }

    public SkinProcessingStats processingStats() {
        return this.provider.processingStats();
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
            for (Consumer<UUID> readyListener : this.readyListeners) readyListener.accept(playerUuid);
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("We could not prepare your skin. Try again later."));
            }
        });
    }

    private static PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        return PlayerSkinSource.fromProfile(player.getGameProfile(), EmoteMod.SERVER.services().sessionService());
    }

    private PlayerSkinSource resolvePlayerSkinSource(ServerPlayer player) {
        try {
            return this.playerSkinSourceResolver.apply(player);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Could not resolve player skin; using any prepared default skin", exception);
            return null;
        }
    }

    private record SkinIdentity(String textureHash, boolean slimModel) {
    }

}
