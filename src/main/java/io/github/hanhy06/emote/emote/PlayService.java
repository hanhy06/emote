package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.ApiEvents;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PlayService {
    private final EmoteRegistry emoteRegistry;
    private final PlayPermissionChecker playPermissionChecker;
    private final PlaybackStarter emoteStarter;
    private final PlayEventDispatcher eventDispatcher;

    public PlayService(
        EmoteRegistry emoteRegistry,
        PermissionService permissionService,
        PlaybackManager playbackManager,
        ApiEvents apiEvents
    ) {
        this(
            emoteRegistry,
            (player, emote) -> permissionService.canPlay(player, emote.id()),
            playbackManager::start,
            apiEvents::beforePlay
        );
    }

    PlayService(
        EmoteRegistry emoteRegistry,
        PlayPermissionChecker playPermissionChecker,
        PlaybackStarter emoteStarter,
        PlayEventDispatcher eventDispatcher
    ) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
        this.emoteStarter = emoteStarter;
        this.eventDispatcher = eventDispatcher;
    }

    public PlayResult play(ServerPlayer player, String id) {
        return play(player, id, PlaySource.COMMAND);
    }

    public PlayResult play(ServerPlayer player, String id, PlaySource source) {
        EmoteDefinition emote = this.emoteRegistry.findDefinition(id);
        if (emote == null) {
            return PlayResult.failure("Unknown: " + id);
        }
        if (!emote.standalone()) {
            return PlayResult.failure("Sequence-only animation: " + id);
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
        boolean canPlay(ServerPlayer player, EmoteDefinition emote);
    }

    @FunctionalInterface
    interface PlaybackStarter {
        PlayResult start(ServerPlayer player, EmoteDefinition emote);
    }

    @FunctionalInterface
    interface PlayEventDispatcher {
        Component beforePlay(ServerPlayer player, EmoteDefinition emote, PlaySource source);
    }
}
