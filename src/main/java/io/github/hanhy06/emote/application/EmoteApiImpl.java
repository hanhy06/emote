package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.LoadedAnimation;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlaybackParticipant;
import io.github.hanhy06.emote.playback.PlaybackSession;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EmoteApiImpl extends EmoteApi {
    private final EmoteCatalog emoteRegistry;
    private final EmotePlayService playService;
    private final PlaybackEngine playbackEngine;
    private final ApiEventDispatcher events;
    private final WheelSyncService wheelSyncService;
    private final AnimationServerPreparer animationValidator;

    public EmoteApiImpl(
        EmoteCatalog emoteRegistry,
        EmotePlayService playService,
        PlaybackEngine playbackEngine,
        ApiEventDispatcher events,
        WheelSyncService wheelSyncService,
        AnimationServerPreparer animationValidator
    ) {
        this.emoteRegistry = Objects.requireNonNull(emoteRegistry, "emoteRegistry");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playbackEngine = Objects.requireNonNull(playbackEngine, "playbackEngine");
        this.events = Objects.requireNonNull(events, "events");
        this.wheelSyncService = Objects.requireNonNull(wheelSyncService, "wheelSyncService");
        this.animationValidator = Objects.requireNonNull(animationValidator, "animationValidator");
    }

    @Override
    public PlayResult play(ServerPlayer player, Identifier emoteId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(emoteId, "emoteId");
        requireServerThread();
        return this.playService.play(player, emoteId.toString(), PlaySource.API);
    }

    @Override
    public boolean stop(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        return this.playbackEngine.stop(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public EmoteRegistration register(EmoteAnimation animation) throws EmoteAnimationLoadException {
        Objects.requireNonNull(animation, "animation");
        requireServerThread();
        Path sourcePath = Path.of("api", animation.id().getNamespace(), animation.id().getPath() + ".json");
        LoadedAnimation loaded = new LoadedAnimation(
            sourcePath,
            "api:" + animation.id(),
            animation
        );
        PreparedEmote emote = PreparedEmote.from(this.animationValidator.prepare(loaded));
        UUID registrationId = this.emoteRegistry.registerApi(emote);
        this.wheelSyncService.syncAll();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Optional<EmoteInfo> find(Identifier emoteId) {
        Objects.requireNonNull(emoteId, "emoteId");
        return Optional.ofNullable(this.emoteRegistry.findDefinition(emoteId.toString()))
            .map(ApiEventDispatcher::toInfo);
    }

    @Override
    public List<EmoteInfo> getAll() {
        return this.emoteRegistry.getAllDefinitions().stream()
            .map(ApiEventDispatcher::toInfo)
            .toList();
    }

    @Override
    public Optional<PlaybackInfo> getPlayback(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlaybackSession session = this.playbackEngine.findActive(player.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        PlaybackParticipant participant = session.participant(player.getUUID());
        return participant == null ? Optional.empty() : Optional.of(ApiEventDispatcher.toPlaybackInfo(session, participant));
    }

    @Override
    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return this.events.addPlayListener(listener);
    }

    @Override
    public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) {
        return this.events.addPlaybackListener(listener);
    }

    private void requireServerThread() {
        if (!Emote.SERVER.isSameThread()) {
            throw new IllegalStateException("Emote API mutations must run on the server thread.");
        }
    }

    private final class ApiRegistration implements EmoteRegistration {
        private final Identifier id;
        private final UUID registrationId;

        private ApiRegistration(Identifier id, UUID registrationId) {
            this.id = id;
            this.registrationId = registrationId;
        }

        @Override
        public Identifier id() {
            return this.id;
        }

        @Override
        public boolean isRegistered() {
            return EmoteApiImpl.this.emoteRegistry.isApiRegistrationActive(
                this.id.toString(),
                this.registrationId
            );
        }

        @Override
        public boolean unregister() {
            requireServerThread();
            if (!EmoteApiImpl.this.emoteRegistry.unregisterApi(this.id.toString(), this.registrationId)) {
                return false;
            }
            EmoteApiImpl.this.playbackEngine.stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            EmoteApiImpl.this.wheelSyncService.syncAll();
            return true;
        }
    }
}
