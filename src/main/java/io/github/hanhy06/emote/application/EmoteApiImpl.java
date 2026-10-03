package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.content.loader.AnimationContentResolver;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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
        return play(player, emoteId, PlayOptions.createDefault());
    }

    @Override
    public PlayResult play(ServerPlayer player, Identifier emoteId, PlayOptions options) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(emoteId, "emoteId");
        Objects.requireNonNull(options, "options");
        requireServerThread();
        return this.playService.play(player, emoteId.toString(), PlaySource.API, options);
    }

    @Override
    public boolean stop(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        requireServerThread();
        return this.playerPlaybackManager.stop(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public boolean stop(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireServerThread();
        return this.playerPlaybackManager.engine().stop(sessionId, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public boolean setPlacement(UUID sessionId, PlaybackPlacement placement) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(placement, "placement");
        requireServerThread();
        return this.playerPlaybackManager.setPlacement(sessionId, placement)
            || this.playerPlaybackManager.engine().setPlacement(sessionId, placement);
    }

    @Override
    public Optional<Vec3> getNodeWorldPosition(UUID sessionId, String nodeId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(nodeId, "nodeId");
        requireServerThread();
        return Optional.ofNullable(this.playerPlaybackManager.engine().findSession(sessionId))
            .flatMap(session -> session.nodeWorldPosition(nodeId));
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
        PreparedEmote emote = PreparedEmote.from(this.contentResolver.resolve(loaded));
        UUID registrationId = this.emoteCatalog.register(emote);
        this.changeNotifier.notifyChanged();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Registration register(EmoteSequence sequence) {
        Objects.requireNonNull(sequence, "sequence");
        requireServerThread();
        var animations = this.emoteCatalog.animations().stream().collect(Collectors.toMap(PreparedEmote::id, Function.identity()));
        PreparedSequence prepared = PreparedSequence.resolve(sequence, animations);
        UUID registrationId = this.emoteCatalog.register(prepared);
        this.changeNotifier.notifyChanged();
        return new ApiRegistration(sequence.id(), registrationId);
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
        requireServerThread();
        PlaybackSession session = this.playerPlaybackManager.findActive(player.getUUID());
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.playbackInfo());
    }

    @Override
    public ListenerRegistration addPlayListener(EmotePlayListener listener) {
        return this.events.addPlayListener(listener);
    }

    @Override
    public Optional<PlaybackInfo> getPlayback(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireServerThread();
        return Optional.ofNullable(this.playerPlaybackManager.engine().findSession(sessionId)).map(PlaybackSession::playbackInfo);
    }

    @Override
    public Optional<PlaybackTimeline> getTimeline(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireServerThread();
        return Optional.ofNullable(this.playerPlaybackManager.engine().findSession(sessionId))
            .map(session -> session.playback().timeline());
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
            List<String> previousIds = EmoteApiImpl.this.emoteCatalog.emotes().stream().map(emote -> emote.id()).toList();
            if (!EmoteApiImpl.this.emoteCatalog.unregister(this.id.toString(), this.registrationId)) {
                return false;
            }
            EmoteApiImpl.this.playerPlaybackManager.engine().stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            for (String previousId : previousIds) {
                if (!previousId.equals(this.id.toString()) && EmoteApiImpl.this.emoteCatalog.find(previousId) == null) {
                    EmoteApiImpl.this.playerPlaybackManager.engine().stopById(previousId, PlaybackStopReason.EMOTE_REMOVED);
                }
            }
            EmoteApiImpl.this.changeNotifier.notifyChanged();
            return true;
        }
    }
}
