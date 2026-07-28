package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.animation.EmoteAnimationServerValidator;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EmoteApiImpl extends EmoteApi {
    private final EmoteRegistry emoteRegistry;
    private final PlayService playService;
    private final PlaybackManager playbackManager;
    private final EmoteApiEvents events;
    private final WheelSyncService wheelSyncService;
    private final EmoteAnimationServerValidator animationValidator;

    public EmoteApiImpl(
        EmoteRegistry emoteRegistry,
        PlayService playService,
        PlaybackManager playbackManager,
        EmoteApiEvents events,
        WheelSyncService wheelSyncService,
        EmoteAnimationServerValidator animationValidator
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
        return this.playbackManager.stopEmote(player, PlaybackStopReason.MANUAL) != null;
    }

    @Override
    public EmoteRegistration register(EmoteAnimation animation) throws EmoteAnimationLoadException {
        Objects.requireNonNull(animation, "animation");
        MinecraftServer server = getServerOnCurrentThread();
        Path sourcePath = Path.of("api", animation.id().getNamespace(), animation.id().getPath() + ".json");
        EmoteAnimation.Loaded loaded = new EmoteAnimation.Loaded(
            sourcePath,
            "api:" + animation.id(),
            animation
        );
        RegisteredEmote emote = RegisteredEmote.from(this.animationValidator.prepare(loaded, server));
        UUID registrationId = this.emoteRegistry.registerApi(emote);
        this.wheelSyncService.syncAll();
        return new ApiRegistration(animation.id(), registrationId);
    }

    @Override
    public Optional<EmoteInfo> find(Identifier emoteId) {
        Objects.requireNonNull(emoteId, "emoteId");
        return Optional.ofNullable(this.emoteRegistry.find(emoteId.toString()))
            .map(EmoteApiEvents::toInfo);
    }

    @Override
    public List<EmoteInfo> getAll() {
        return this.emoteRegistry.getAll().stream()
            .map(EmoteApiEvents::toInfo)
            .toList();
    }

    @Override
    public Optional<PlaybackInfo> getPlayback(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ActiveEmote activeEmote = this.playbackManager.findActiveEmote(player.getUUID());
        return Optional.ofNullable(activeEmote).map(EmoteApiEvents::toPlaybackInfo);
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
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            throw new IllegalStateException("The server is not running.");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Emote API mutations must run on the server thread.");
        }
    }

    private MinecraftServer getServerOnCurrentThread() {
        requireServerThread();
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            throw new IllegalStateException("The server is not running.");
        }
        return server;
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
            EmoteApiImpl.this.playbackManager.stopId(
                this.id.toString(),
                PlaybackStopReason.EMOTE_REMOVED
            );
            EmoteApiImpl.this.wheelSyncService.syncAll();
            return true;
        }
    }
}
