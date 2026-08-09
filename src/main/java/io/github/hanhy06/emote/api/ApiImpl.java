package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.ActivePlayback;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApiImpl extends EmoteApi {
    private final EmoteRegistry emoteRegistry;
    private final PlayService playService;
    private final PlaybackManager playbackManager;
    private final ApiEvents events;
    private final WheelSyncService wheelSyncService;
    private final AnimationServerPreparer animationValidator;

    public ApiImpl(
        EmoteRegistry emoteRegistry,
        PlayService playService,
        PlaybackManager playbackManager,
        ApiEvents events,
        WheelSyncService wheelSyncService,
        AnimationServerPreparer animationValidator
    ) {
        this.emoteRegistry = Objects.requireNonNull(emoteRegistry, "emoteRegistry");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playbackManager = Objects.requireNonNull(playbackManager, "playbackManager");
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
        return this.playbackManager.stop(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public EmoteRegistration register(EmoteAnimation animation) throws EmoteAnimationLoadException {
        Objects.requireNonNull(animation, "animation");
        requireServerThread();
        Path sourcePath = Path.of("api", animation.id().getNamespace(), animation.id().getPath() + ".json");
        EmoteAnimation.Loaded loaded = new EmoteAnimation.Loaded(
            sourcePath,
            "api:" + animation.id(),
            animation
        );
        RegisteredEmote emote = RegisteredEmote.from(this.animationValidator.prepare(loaded));
        UUID registrationId = this.emoteRegistry.registerApi(emote);
        this.wheelSyncService.syncAll();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Optional<EmoteInfo> find(Identifier emoteId) {
        Objects.requireNonNull(emoteId, "emoteId");
        return Optional.ofNullable(this.emoteRegistry.find(emoteId.toString()))
            .map(ApiEvents::toInfo);
    }

    @Override
    public List<EmoteInfo> getAll() {
        return this.emoteRegistry.getAll().stream()
            .map(ApiEvents::toInfo)
            .toList();
    }

    @Override
    public Optional<PlaybackInfo> getPlayback(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ActivePlayback activeEmote = this.playbackManager.findActive(player.getUUID());
        return Optional.ofNullable(activeEmote).map(ApiEvents::toPlaybackInfo);
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
            return ApiImpl.this.emoteRegistry.isApiRegistrationActive(
                this.id.toString(),
                this.registrationId
            );
        }

        @Override
        public boolean unregister() {
            requireServerThread();
            if (!ApiImpl.this.emoteRegistry.unregisterApi(this.id.toString(), this.registrationId)) {
                return false;
            }
            ApiImpl.this.playbackManager.stopById(this.id.toString(), PlaybackStopReason.EMOTE_REMOVED);
            ApiImpl.this.wheelSyncService.syncAll();
            return true;
        }
    }
}
