package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.github.hanhy06.emote.skin.model.PlayerSkinPreparation.State;

public class PlayerSkinManager implements ConfigListener {
    private final SkinBakeCoordinator bakeCoordinator;
    private final Map<String, CompletableFuture<PlayerSkinSource>> namedSources = new HashMap<>();
    private final List<Runnable> defaultReadyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, PlayerSkinSource.Key> connectedSkins = new HashMap<>();
    private Set<PlayerSkinRegion> modelRegions = Set.of();
    private Map<String, PreparedAnimation> animations = Map.of();
    private Set<Path> filePaths = Set.of();
    private final Set<UUID> embeddedSources = new HashSet<>();
    private PlayerSkinSource configuredDefault;
    private List<PreparedAnimation> pendingDefaults = List.of();
    private CompletableFuture<Void> defaultWrites = CompletableFuture.completedFuture(null);
    private volatile long sourceGeneration, catalogRevision;
    private MinecraftServer server;

    public PlayerSkinManager(SkinBakeCoordinator bakeCoordinator) {
        this.bakeCoordinator = Objects.requireNonNull(bakeCoordinator, "bakeCoordinator");
        this.bakeCoordinator.setListener(new SkinBakeCoordinator.Listener() {
            @Override public void onReady(UUID playerUuid, long generation) { notifySkinReady(playerUuid, generation, false); }
            @Override public void onUnavailable(UUID playerUuid, long generation) { notifySkinReady(playerUuid, generation, false); }
            @Override public void onFailed(UUID playerUuid, long generation) { notifySkinReady(playerUuid, generation, true); }
        });
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.server = EmoteMod.SERVER;
        long generation = ++this.sourceGeneration;
        this.namedSources.clear();
        this.configuredDefault = null;
        this.pendingDefaults = List.of();
        this.bakeCoordinator.onConfigReload(newConfig);
        if (!newConfig.defaultSkin().isEmpty()) {
            namedSource(newConfig.defaultSkin()).thenCompose(source -> source == null
                ? CompletableFuture.completedFuture(null) : this.bakeCoordinator.embed(source)).whenComplete((source, exception) -> {
                if (generation != this.sourceGeneration) return;
                if (source == null || exception != null) {
                    EmoteMod.LOGGER.warn("Could not prepare default skin for {}; retaining the skins embedded in emote JSON", newConfig.defaultSkin(), exception);
                    return;
                }
                this.server.execute(() -> {
                    if (generation != this.sourceGeneration) return;
                    this.configuredDefault = source;
                    synchronizeDefault();
                });
            });
        }
    }

    public void setEmotes(List<PreparedAnimation> animations, Set<Path> filePaths) {
        this.animations = animations.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(PreparedAnimation::id, animation -> animation));
        this.filePaths = Set.copyOf(filePaths);
        this.catalogRevision++;
        this.pendingDefaults = List.of();
        this.modelRegions = animations.stream().flatMap(animation -> animation.skinBindings().stream()).map(SkinBinding::region)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.embeddedSources.clear();
        for (PreparedAnimation animation : animations) {
            PlayerSkinSource source = animation.model().skin().defaultSource();
            if (source == null) continue;
            this.embeddedSources.add(source.playerUuid());
            this.bakeCoordinator.prepare(source.withOverride(animation.model().skin().override()), regions(animation.skinBindings()));
        }
        synchronizeDefault();
    }

    private void synchronizeDefault() {
        PlayerSkinSource source = this.configuredDefault;
        if (source == null || this.animations.isEmpty()) return;
        long generation = this.sourceGeneration, revision = this.catalogRevision;
        List<PreparedAnimation> animations = List.copyOf(this.animations.values());
        Set<Path> paths = this.filePaths;
        String base64 = Base64.getEncoder().encodeToString(source.png());
        this.defaultWrites = this.defaultWrites.handle((ignored, exception) -> null).thenRunAsync(() -> {
            List<PreparedAnimation> saved = new ArrayList<>();
            for (PreparedAnimation animation : animations) {
                if (generation != this.sourceGeneration || revision != this.catalogRevision) return;
                try {
                    if (paths.contains(animation.sourcePath())) EmoteDirectoryLoader.updateDefaultSkin(animation.sourcePath(), animation.id(), base64);
                    saved.add(animation);
                } catch (java.io.IOException | io.github.hanhy06.emote.api.EmoteLoadException exception) {
                    EmoteMod.LOGGER.warn("Could not save default skin in {}; retaining its previous skin", animation.sourcePath(), exception);
                }
            }
            this.server.execute(() -> {
                if (generation != this.sourceGeneration || revision != this.catalogRevision) return;
                this.embeddedSources.add(source.playerUuid());
                this.pendingDefaults = List.copyOf(saved);
                if (publishDefault()) notifyDefaultReady();
            });
        }, Util.nonCriticalIoPool());
    }

    private boolean publishDefault() {
        PlayerSkinSource source = this.configuredDefault;
        if (source == null || this.pendingDefaults.isEmpty()) return false;
        boolean ready = true;
        for (PreparedAnimation animation : this.pendingDefaults) {
            ready &= this.bakeCoordinator.prepare(source.withOverride(animation.model().skin().override()), regions(animation.skinBindings())).state() == State.READY;
        }
        if (!ready) return false;
        for (PreparedAnimation animation : this.pendingDefaults) animation.model().skin().setDefaultSource(source);
        this.pendingDefaults = List.of();
        return true;
    }

    public Preparation prepareNamedSkin(String name, PreparedEmote emote) {
        CompletableFuture<PlayerSkinSource> source = name.isBlank() ? CompletableFuture.completedFuture(null) : namedSource(name);
        return prepareSkinSource(source.getNow(null), emote.animations(), !source.isDone());
    }

    private CompletableFuture<PlayerSkinSource> namedSource(String name) {
        String normalized = name.strip();
        long generation = this.sourceGeneration;
        return this.namedSources.computeIfAbsent(normalized.toLowerCase(Locale.ROOT), ignored ->
            resolveNamedSkinSource(normalized).handle((source, exception) -> {
                if (source == null || exception != null) {
                    EmoteMod.LOGGER.warn("Could not resolve skin for {}; retaining any prepared fallback skin", normalized, exception);
                    return null;
                }
                return source;
            }).whenComplete((source, exception) -> {
                if (generation != this.sourceGeneration) return;
                this.server.execute(() -> {
                    if (generation == this.sourceGeneration) this.defaultReadyListeners.forEach(Runnable::run);
                });
            }));
    }

    private static CompletableFuture<PlayerSkinSource> resolveNamedSkinSource(String name) {
        MinecraftServer server = EmoteMod.SERVER;
        return CompletableFuture.supplyAsync(() -> server.services().profileResolver().fetchByName(name)
            .map(profile -> PlayerSkinSource.fromProfile(profile, server.services().sessionService())).orElse(null), Util.nonCriticalIoPool())
            .orTimeout(15, TimeUnit.SECONDS);
    }

    public Preparation preparePlayerSkin(ServerPlayer player, PreparedEmote emote) {
        return preparePlayerSkins(player, emote.animations());
    }

    public Preparation preparePlayerSkins(ServerPlayer player, List<PreparedAnimation> animations) {
        return prepareSkinSource(animations.stream().allMatch(animation -> animation.skinBindings().isEmpty()) ? null : resolvePlayerSkinSource(player), animations, false);
    }

    public Preparation prepareSkinSource(PlayerSkinSource source, List<PreparedAnimation> animations, boolean resolving) {
        Preparation player = source == null && !resolving ? new Preparation(Map.of(), State.UNAVAILABLE, 0)
            : prepareSkins(source, animations, resolving);
        if (player.ready()) return player;
        Preparation fallback = prepareSkins(null, animations, false);
        if (fallback.ready()) return fallback;
        State state = player.preparing() || fallback.preparing() ? State.PREPARING
            : player.state() == State.FAILED || fallback.state() == State.FAILED ? State.FAILED : State.UNAVAILABLE;
        return new Preparation(Map.of(), state, Math.max(player.progressPercent(), fallback.progressPercent()));
    }

    private Preparation prepareSkins(PlayerSkinSource source, List<PreparedAnimation> animations, boolean resolving) {
        Map<String, Map<PlayerSkinRegion, String>> textures = new HashMap<>();
        boolean ready = true, preparing = false, failed = false;
        int progress = 0;
        for (PreparedAnimation animation : animations) {
            Set<PlayerSkinRegion> regions = regions(animation.skinBindings());
            PlayerSkinSource selected = source == null ? animation.model().skin().defaultSource() : source;
            PlayerSkinPreparation preparation = regions.isEmpty() ? new PlayerSkinPreparation(Map.of(), State.READY, 100)
                : resolving ? new PlayerSkinPreparation(null, State.PREPARING, 0)
                : selected == null ? new PlayerSkinPreparation(null, State.UNAVAILABLE, 0)
                : this.bakeCoordinator.prepare(selected.withOverride(animation.model().skin().override()), regions);
            ready &= preparation.state() == State.READY;
            preparing |= preparation.preparing();
            failed |= preparation.state() == State.FAILED;
            progress += preparation.progressPercent();
            if (preparation.state() == State.READY) textures.put(animation.id(), preparation.textures());
        }
        return new Preparation(ready ? Map.copyOf(textures) : Map.of(), ready ? State.READY : preparing ? State.PREPARING : failed ? State.FAILED : State.UNAVAILABLE,
            ready ? 100 : animations.isEmpty() ? 0 : progress / animations.size());
    }

    private static Set<PlayerSkinRegion> regions(List<SkinBinding> bindings) {
        return bindings.stream().map(SkinBinding::region).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void checkPlayerSkin(ServerPlayer player) {
        PlayerSkinSource source = resolvePlayerSkinSource(player);
        if (source == null || this.modelRegions.isEmpty()) return;
        PlayerSkinSource.Key identity = new PlayerSkinSource.Key(source.textureHash(), source.slimModel());
        PlayerSkinSource.Key previous = this.connectedSkins.put(source.playerUuid(), identity);
        if (identity.equals(previous)) return;
        PlayerSkinPreparation preparation = this.bakeCoordinator.prepare(source, this.modelRegions);
        if (previous != null && preparation.state() == State.READY) {
            for (Consumer<UUID> listener : this.readyListeners) listener.accept(source.playerUuid());
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
        this.sourceGeneration++;
        this.catalogRevision++;
        this.configuredDefault = null;
        this.pendingDefaults = List.of();
        this.namedSources.clear();
        this.connectedSkins.clear();
        this.bakeCoordinator.cancelPendingBakes();
    }

    public SkinProcessingStats processingStats() {
        return this.bakeCoordinator.processingStats();
    }

    private void notifyDefaultReady() {
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            for (Consumer<UUID> listener : this.readyListeners) listener.accept(player.getUUID());
        }
        this.defaultReadyListeners.forEach(Runnable::run);
    }

    private void notifySkinReady(UUID playerUuid, long generation, boolean failed) {
        if (!this.bakeCoordinator.isCurrent(generation)) return;
        this.server.execute(() -> {
            if (!this.bakeCoordinator.isCurrent(generation)) return;
            if (this.embeddedSources.contains(playerUuid)) {
                if (!failed) publishDefault();
                notifyDefaultReady();
            }
            else {
                for (Consumer<UUID> listener : this.readyListeners) listener.accept(playerUuid);
                this.defaultReadyListeners.forEach(Runnable::run);
                ServerPlayer player = this.server.getPlayerList().getPlayer(playerUuid);
                if (failed && player != null) player.sendSystemMessage(Component.literal("Your skin could not be prepared. Emotes may use a default skin. Ask a server administrator to check the server log."));
            }
        });
    }

    private PlayerSkinSource resolvePlayerSkinSource(ServerPlayer player) {
        try {
            return PlayerSkinSource.fromProfile(player.getGameProfile(), EmoteMod.SERVER.services().sessionService());
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Could not resolve player skin; using any prepared default skin", exception);
            return null;
        }
    }

    public record Preparation(Map<String, Map<PlayerSkinRegion, String>> textures, State state, int progressPercent) {
        public boolean ready() { return this.state == State.READY; }
        public boolean preparing() { return this.state == State.PREPARING; }
    }
}
