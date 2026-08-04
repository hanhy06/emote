package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.EmoteApiEvents;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PlayService {
    public static PlayService INSTANCE;

    private final EmoteRegistry emoteRegistry;
    private final PlayPermissionChecker playPermissionChecker;
    private final EmoteStarter emoteStarter;
    private final PlayEventDispatcher eventDispatcher;

    public PlayService(
        EmoteRegistry emoteRegistry,
        PermissionService permissionService,
        PlaybackManager playbackManager,
        EmoteApiEvents apiEvents
    ) {
        this(
            emoteRegistry,
            (player, emote) -> permissionService.canPlay(player, emote.id()),
            playbackManager::startEmote,
            apiEvents::beforePlay
        );
    }

    PlayService(
        EmoteRegistry emoteRegistry,
        PlayPermissionChecker playPermissionChecker,
        EmoteStarter emoteStarter,
        PlayEventDispatcher eventDispatcher
    ) {
        INSTANCE = this;

        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
        this.emoteStarter = emoteStarter;
        this.eventDispatcher = eventDispatcher;
    }

    public PlayResult play(ServerPlayer player, String id) {
        return play(player, id, PlaySource.COMMAND);
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source) {
        RegisteredEmote emote = this.emoteRegistry.find(id);
        if (emote == null) {
            return PlayResult.failure("Unknown: " + id);
        }
        if (!this.playPermissionChecker.canPlay(player, emote)) {
            return PlayResult.failure("No emote permission.");
        }
        Component cancellationMessage = this.eventDispatcher.beforePlay(player, emote, source);
        if (cancellationMessage != null) {
            return PlayResult.failure(cancellationMessage);
        }
        return this.emoteStarter.start(player, emote);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, RegisteredEmote emote);
    }

    @FunctionalInterface
    interface EmoteStarter {
        PlayResult start(ServerPlayer player, RegisteredEmote emote);
    }

    @FunctionalInterface
    interface PlayEventDispatcher {
        Component beforePlay(ServerPlayer player, RegisteredEmote emote, PlaySource source);
    }
}
