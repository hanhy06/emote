package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public class PlayableEmoteService {
    private final EmoteRegistry emoteRegistry;
    private final PlayPermissionChecker playPermissionChecker;

    public PlayableEmoteService(EmoteRegistry emoteRegistry, PermissionService permissionService) {
        this(emoteRegistry, (player, definition) -> permissionService.canPlay(player, definition.namespace()));
    }

    PlayableEmoteService(EmoteRegistry emoteRegistry, PlayPermissionChecker playPermissionChecker) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
    }

    public List<PlayableEmote> getPlayableEmotes(ServerPlayer player) {
        return this.emoteRegistry.getDefinitions().stream()
            .filter(definition -> canPlay(player, definition))
            .sorted(Comparator.comparing(EmoteDefinition::name).thenComparing(EmoteDefinition::commandName))
            .map(definition -> new PlayableEmote(definition.commandName(), definition.name(), definition.description()))
            .toList();
    }

    public List<String> getPlayablePlayNames(ServerPlayer player) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
            if (canPlay(player, definition)) {
                names.putIfAbsent(definition.commandName(), definition.commandName());
                names.putIfAbsent(definition.namespace(), definition.namespace());
            }
        }
        return List.copyOf(names.values());
    }

    public PlayableEmoteSelectionResult findSelection(ServerPlayer player, String commandName) {
        EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName);
        if (definition == null) {
            return PlayableEmoteSelectionResult.failure("Unknown: " + commandName);
        }
        if (!canPlay(player, definition)) {
            return PlayableEmoteSelectionResult.failure("No emote permission.");
        }
        return PlayableEmoteSelectionResult.success(new PlayableEmoteSelection(definition));
    }

    private boolean canPlay(ServerPlayer player, EmoteDefinition definition) {
        return this.playPermissionChecker.canPlay(player, definition);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, EmoteDefinition definition);
    }
}
