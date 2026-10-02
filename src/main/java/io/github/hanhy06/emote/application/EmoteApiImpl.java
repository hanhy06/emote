package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationContentResolver;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.playback.PlayerPlaybackState;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
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
    private final PlayerPlaybackManager playerPlaybackManager;
    private final ApiEventDispatcher events;
    private final ChangeNotifier changeNotifier;
    private final AnimationContentResolver contentResolver;

    public EmoteApiImpl(
        EmoteCatalog emoteCatalog,
        EmotePlayService playService,
        PlayerPlaybackManager playerPlaybackManager,
        ApiEventDispatcher events,
        ChangeNotifier changeNotifier,
        AnimationContentResolver contentResolver
    ) {
        this.emoteCatalog = Objects.requireNonNull(emoteCatalog, "emoteCatalog");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playerPlaybackManager = Objects.requireNonNull(playerPlaybackManager, "playerPlaybackManager");
        this.events = Objects.requireNonNull(events, "events");
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
        return this.playerPlaybackManager.stop(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public Registration register(EmoteAnimation animation) throws EmoteAnimationLoadException {
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
        PlaybackSession session = this.playerPlaybackManager.findActive(player.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.playbackInfo(player.getUUID()));
    }

    @Override
    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return this.events.addPlayListener(listener);
    }

    @Override
    public Optional<PlaybackInfo> getPlayback(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireServerThread();
        return this.playerPlaybackManager.playbackInfo(sessionId);
    }

    @Override
    public Registration registerCallbacks(Identifier id, EmoteCallbacks callbacks) {
        return this.playerPlaybackManager.engine().callbackRegistry().register(id, callbacks);
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

    @FunctionalInterface
    public interface ChangeNotifier {
        void notifyChanged();
    }

    private final class ApiRegistration implements Registration {
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
            EmoteApiImpl.this.playerPlaybackManager.engine().stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            EmoteApiImpl.this.changeNotifier.notifyChanged();
            return true;
        }
    }
}
