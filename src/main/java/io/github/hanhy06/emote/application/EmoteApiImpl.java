package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationContentResolver;
import io.github.hanhy06.emote.playback.PlaybackEngine;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import io.github.hanhy06.emote.playback.timeline.NamedCallbackDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EmoteApiImpl extends EmoteApi {
    private final EmoteCatalog emoteCatalog;
    private final EmotePlayService playService;
    private final PlaybackEngine playbackEngine;
    private final ApiEventDispatcher events;
    private final NamedCallbackDispatcher callbacks;
    private final ChangeNotifier changeNotifier;
    private final AnimationContentResolver contentResolver;

    public EmoteApiImpl(
        EmoteCatalog emoteCatalog,
        EmotePlayService playService,
        PlaybackEngine playbackEngine,
        ApiEventDispatcher events,
        NamedCallbackDispatcher callbacks,
        ChangeNotifier changeNotifier,
        AnimationContentResolver contentResolver
    ) {
        this.emoteCatalog = Objects.requireNonNull(emoteCatalog, "emoteCatalog");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playbackEngine = Objects.requireNonNull(playbackEngine, "playbackEngine");
        this.events = Objects.requireNonNull(events, "events");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.changeNotifier = Objects.requireNonNull(changeNotifier, "changeNotifier");
        this.contentResolver = Objects.requireNonNull(contentResolver, "contentResolver");
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
        PreparedAnimation emote = PreparedAnimation.from(this.contentResolver.resolve(loaded));
        UUID registrationId = this.emoteCatalog.register(emote);
        this.changeNotifier.notifyChanged();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Optional<EmoteInfo> find(Identifier emoteId) {
        Objects.requireNonNull(emoteId, "emoteId");
        return Optional.ofNullable(this.emoteCatalog.find(emoteId.toString()))
            .map(ApiEventDispatcher::toInfo);
    }

    @Override
    public List<EmoteInfo> getAll() {
        return this.emoteCatalog.emotes().stream()
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

    @Override
    public ListenerRegistration addCallbackListener(Identifier name, EmoteCallbackListener listener) {
        return this.callbacks.addListener(name, listener);
    }

    private void requireServerThread() {
        if (!EmoteMod.SERVER.isSameThread()) {
            throw new IllegalStateException("Emote API mutations must run on the server thread.");
        }
    }

    @FunctionalInterface
    public interface ChangeNotifier {
        void notifyChanged();
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
            return EmoteApiImpl.this.emoteCatalog.isApiRegistrationActive(
                this.id.toString(),
                this.registrationId
            );
        }

        @Override
        public boolean unregister() {
            requireServerThread();
            if (!EmoteApiImpl.this.emoteCatalog.unregister(this.id.toString(), this.registrationId)) {
                return false;
            }
            EmoteApiImpl.this.playbackEngine.stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            EmoteApiImpl.this.changeNotifier.notifyChanged();
            return true;
        }
    }
}
