package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.PlaybackSession;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EmoteApiImpl extends EmoteApi {
    private final EmoteCatalog emoteCatalog;
    private final EmotePlayService playService;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final PlaybackEngine engine;
    private final ApiEventDispatcher events;
    private final Runnable changeNotifier;

    public EmoteApiImpl(
        EmoteCatalog emoteCatalog,
        EmotePlayService playService,
        PlayerPlaybackManager playerPlaybackManager,
        PlaybackEngine engine,
        ApiEventDispatcher events,
        Runnable changeNotifier
    ) {
        this.emoteCatalog = Objects.requireNonNull(emoteCatalog, "emoteCatalog");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playerPlaybackManager = Objects.requireNonNull(playerPlaybackManager, "playerPlaybackManager");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.events = Objects.requireNonNull(events, "events");
        this.changeNotifier = Objects.requireNonNull(changeNotifier, "changeNotifier");
    }

    @Override
    public PlayResult play(ServerPlayer player, Identifier emoteId) {
        return play(player, emoteId, PlaybackPlacement.actor());
    }

    @Override
    public PlayResult play(ServerPlayer player, Identifier emoteId, PlaybackPlacement placement) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(emoteId, "emoteId");
        Objects.requireNonNull(placement, "placement");
        requireServerThread();
        return this.playService.play(player, emoteId.toString(), PlaySource.API, placement);
    }

    @Override
    public boolean stop(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        return this.playerPlaybackManager.stop(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public Registration register(EmoteAnimation animation) throws EmoteLoadException {
        Objects.requireNonNull(animation, "animation");
        requireServerThread();
        UUID registrationId = this.emoteCatalog.register(PreparedAnimation.prepare(new LoadedAnimation(
            Path.of("api", animation.id().getNamespace(), animation.id().getPath() + ".json"),
            animation
        )));
        this.changeNotifier.run();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Registration register(EmoteSequence sequence) throws EmoteLoadException {
        Objects.requireNonNull(sequence, "sequence");
        requireServerThread();
        var animations = this.emoteCatalog.animations().stream().collect(Collectors.toMap(PreparedAnimation::id, Function.identity()));
        PreparedSequence prepared = PreparedSequence.prepare(sequence, animations);
        UUID registrationId = this.emoteCatalog.register(prepared);
        this.changeNotifier.run();
        return new ApiRegistration(sequence.id(), registrationId);
    }

    @Override
    public Optional<EmoteInfo> get(Identifier emoteId) {
        Objects.requireNonNull(emoteId, "emoteId");
        return Optional.ofNullable(this.emoteCatalog.find(emoteId.toString()))
            .map(PreparedEmote::info);
    }

    @Override
    public List<EmoteInfo> getAll() {
        return this.emoteCatalog.emotes().stream()
            .map(PreparedEmote::info)
            .toList();
    }

    @Override
    public Optional<Playback> getPlayback(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        return Optional.ofNullable(this.playerPlaybackManager.findActive(player.getUUID())).map(PlaybackSession::view);
    }

    @Override
    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return this.events.addPlayListener(listener);
    }

    @Override
    public Optional<Playback> getPlayback(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireServerThread();
        return Optional.ofNullable(this.engine.findSession(sessionId)).map(PlaybackSession::view);
    }

    @Override
    public Registration registerCallbacks(Identifier id, EmoteCallbacks callbacks) {
        return this.engine.callbackRegistry().register(id, callbacks);
    }

    @Override
    public ListenerRegistration addPlaybackListener(EmotePlaybackListener listener) {
        return this.events.addPlaybackListener(listener);
    }

    private void requireServerThread() {
        if (!EmoteMod.SERVER.isSameThread()) {
            throw new IllegalStateException("Emote API mutations must run on the server thread.");
        }
    }

    private final class ApiRegistration implements Registration {
        private final Identifier id;
        private final UUID registrationId;

        private ApiRegistration(Identifier id, UUID registrationId) {
            this.id = id;
            this.registrationId = registrationId;
        }

        @Override
        public Identifier getId() {
            return this.id;
        }

        @Override
        public boolean isRegistered() {
            return EmoteApiImpl.this.emoteCatalog.isApiRegistrationActive(
                this.id.toString(),
                this.registrationId
            );
        }

        @Override
        public boolean unregister() {
            requireServerThread();
            List<String> previousEmoteIds = EmoteApiImpl.this.emoteCatalog.emotes().stream().map(emote -> emote.id()).toList();
            if (!EmoteApiImpl.this.emoteCatalog.unregister(this.id.toString(), this.registrationId)) {
                return false;
            }
            EmoteApiImpl.this.engine.stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            for (String previousEmoteId : previousEmoteIds) {
                if (!previousEmoteId.equals(this.id.toString()) && EmoteApiImpl.this.emoteCatalog.find(previousEmoteId) == null) {
                    EmoteApiImpl.this.engine.stopById(previousEmoteId, PlaybackStopReason.EMOTE_REMOVED);
                }
            }
            EmoteApiImpl.this.changeNotifier.run();
            return true;
        }
    }
}
