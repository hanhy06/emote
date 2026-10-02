package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.playback.molang.EntityMolangQueries;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.SkinBinding;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.Marker;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.random.RandomGenerator;

public final class EntityPlaybackManager {
    private final PlaybackEngine engine;
    private final EmoteCatalog catalog;
    private final PlayerSkinManager skins;
    private final Map<UUID, Entry> markers = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private long catalogRevision;
    private long skinRevision;
    private boolean running;

    public EntityPlaybackManager(PlaybackEngine engine, EmoteCatalog catalog, PlayerSkinManager skins) {
        this.engine = engine;
        this.catalog = catalog;
        this.skins = skins;
        catalog.addListener(ignored -> this.catalogRevision++);
        skins.addReadyListener(ignored -> this.skinRevision++);
        skins.addDefaultReadyListener(() -> this.skinRevision++);
    }

    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Marker marker) this.markers.put(marker.getUUID(), new Entry(marker));
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Entry entry = this.markers.get(entity.getUUID());
            if (entry == null || entry.marker != entity) return;
            this.markers.remove(entity.getUUID());
            if (entry.session != null) this.engine.stop(entry.session, PlaybackStopReason.ENTITY_UNAVAILABLE);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> this.running = true);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> this.running = false);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> this.markers.clear());
    }

    public void tick() {
        if (!this.running) return;
        long tick = EmoteMod.SERVER.getTickCount();
        for (Entry entry : List.copyOf(this.markers.values())) {
            if (entry.marker.isRemoved()) continue;
            MarkerEmoteSettings settings = ((MarkerEmoteAccess) entry.marker).emote$getSettings();
            if (!settings.equals(entry.settings) || entry.catalogRevision != this.catalogRevision) {
                entry.settings = settings;
                entry.catalogRevision = this.catalogRevision;
                entry.blocked = false;
                entry.nextAttempt = tick;
                entry.needsStart = true;
                entry.skinWarning = false;
            }
            if (settings.emoteId().isEmpty()) {
                if (entry.session != null) this.engine.stop(entry.session, PlaybackStopReason.MANUAL);
                entry.needsStart = false;
                continue;
            }
            if (!entry.blocked && entry.needsStart && tick >= entry.nextAttempt) start(entry, tick);
            if (entry.session != null && !entry.needsStart && entry.skinRevision != this.skinRevision) {
                entry.skinRevision = this.skinRevision;
                var preparation = this.skins.prepareNamedSkin(entry.settings.skinName(), entry.skinBindings);
                if (!preparation.preparing()) this.engine.entities().applySkin(entry.session.nodes(), entry.skinBindings, preparation.preparedPlayerSkin());
            }
        }
    }

    private void start(Entry entry, long tick) {
        PlayableEmote definition = this.catalog.find(entry.settings.emoteId());
        if (definition == null) {
            entry.blocked = true;
            EmoteMod.LOGGER.warn("Marker {} references unknown emote {}", entry.marker.getUUID(), entry.settings.emoteId());
            if (entry.session != null && this.catalog.find(entry.session.id()) == null) {
                this.engine.stop(entry.session, PlaybackStopReason.EMOTE_REMOVED);
            }
            return;
        }
        PreparedAnimation animation = switch (definition) {
            case PreparedAnimation prepared -> prepared;
            case PreparedSequence sequence -> sequence.compile(this.random);
        };
        var preparation = this.skins.prepareNamedSkin(entry.settings.skinName(), animation.skinBindings());
        if (preparation.preparing()) {
            entry.nextAttempt = tick + 20;
            return;
        }
        entry.needsStart = false;
        entry.skinRevision = this.skinRevision;
        entry.skinBindings = animation.skinBindings();
        if (!entry.settings.skinName().isEmpty() && !animation.skinBindings().isEmpty()
            && preparation.state() != PlayerSkinPreparation.State.READY && !entry.skinWarning) {
            entry.skinWarning = true;
            EmoteMod.LOGGER.warn("Marker {} skin {} is {}; using any prepared default skin", entry.marker.getUUID(),
                entry.settings.skinName(), preparation.state());
        }
        var lifecycle = new PlaybackEngine.Lifecycle() {
            @Override public void onStarted(PlaybackSession session) { entry.session = session; }
            @Override public @Nullable PlaybackStopReason beforeTick(PlaybackSession session) {
                return entry.marker.isRemoved() || !entry.marker.level().dimension().equals(session.levelKey())
                    ? PlaybackStopReason.ENTITY_UNAVAILABLE : null;
            }
            @Override public void prepareFrame(PlaybackSession session) {
                engine.entities().moveSceneTo(session.nodes(), entry.marker.position());
                engine.entities().updateViewRotation(session.nodes(), entry.marker.getYRot(), 0);
            }
            @Override public void onStopped(PlaybackSession session, PlaybackStopReason reason) {
                if (entry.session != session) return;
                entry.session = null;
                if (reason == PlaybackStopReason.FINISHED || reason == PlaybackStopReason.RELOAD) {
                    entry.needsStart = true;
                    entry.nextAttempt = EmoteMod.SERVER.getTickCount() + 1L;
                } else if (reason != PlaybackStopReason.REPLACED) {
                    entry.blocked = true;
                }
            }
        };
        Marker marker = entry.marker;
        ServerLevel level = (ServerLevel) marker.level();
        var result = this.engine.start(new PlaybackEngine.Request(level, RootTransform.create(marker.position(), marker.getYRot()),
            animation, definition.id(), Map.of("actor", marker), EntityMolangQueries.forEntity(marker),
            EmoteMod.SERVER.createCommandSourceStack().withEntity(marker).withLevel(level)
                .withPosition(marker.position()).withRotation(marker.getRotationVector()),
            preparation.preparedPlayerSkin(), lifecycle), entry.session);
        if (result instanceof PlaybackEngine.StartResult.Failure failure) {
            if (failure.reason() == PlaybackEngine.FailureReason.DISPLAY_LIMIT) {
                entry.needsStart = true;
                entry.nextAttempt = tick + 20;
                return;
            }
            entry.blocked = true;
            EmoteMod.LOGGER.warn("Failed to start marker {} emote {}: {}", marker.getUUID(), definition.id(), failure.message());
        }
    }

    private static final class Entry {
        private final Marker marker;
        private MarkerEmoteSettings settings = MarkerEmoteSettings.EMPTY;
        private @Nullable PlaybackSession session;
        private long catalogRevision = -1;
        private long nextAttempt;
        private boolean needsStart;
        private boolean blocked;
        private boolean skinWarning;
        private long skinRevision;
        private List<SkinBinding> skinBindings = List.of();
        private Entry(Marker marker) { this.marker = marker; }
    }
}
