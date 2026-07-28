package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayResult;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.playback.ActiveEmote;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class EmoteApiImpl extends EmoteApi {
    private final EmoteRegistry emoteRegistry;
    private final PlayService playService;
    private final PlaybackManager playbackManager;
    private final EmoteApiEvents events;

    public EmoteApiImpl(
        EmoteRegistry emoteRegistry,
        PlayService playService,
        PlaybackManager playbackManager,
        EmoteApiEvents events
    ) {
        this.emoteRegistry = Objects.requireNonNull(emoteRegistry, "emoteRegistry");
        this.playService = Objects.requireNonNull(playService, "playService");
        this.playbackManager = Objects.requireNonNull(playbackManager, "playbackManager");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public PlayResult play(ServerPlayer player, Identifier emoteId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(emoteId, "emoteId");
        return this.playService.play(player, emoteId.toString(), PlaySource.API);
    }

    @Override
    public boolean stop(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return this.playbackManager.stopEmote(player, PlaybackStopReason.MANUAL) != null;
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
}
