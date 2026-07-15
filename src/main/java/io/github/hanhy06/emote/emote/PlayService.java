package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import net.minecraft.server.level.ServerPlayer;

public class PlayService {
    private final EmoteRegistry emoteRegistry;
    private final PlayPermissionChecker playPermissionChecker;
    private final EmoteStarter emoteStarter;

    public PlayService(
        EmoteRegistry emoteRegistry,
        PermissionService permissionService,
        PlaybackManager playbackManager
    ) {
        this(emoteRegistry, (player, emote) -> permissionService.canPlay(player, emote.id()), playbackManager::startEmote);
    }

    PlayService(
        EmoteRegistry emoteRegistry,
        PlayPermissionChecker playPermissionChecker,
        EmoteStarter emoteStarter
    ) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
        this.emoteStarter = emoteStarter;
    }

    public PlayResult play(ServerPlayer player, String id) {
        RegisteredEmote emote = this.emoteRegistry.find(id);
        if (emote == null) {
            return PlayResult.failure("Unknown: " + id);
        }
        if (!this.playPermissionChecker.canPlay(player, emote)) {
            return PlayResult.failure("No emote permission.");
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
}
