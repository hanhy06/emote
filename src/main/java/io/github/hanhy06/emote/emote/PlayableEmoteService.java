package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

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

    public List<PlayableEmote> getPlayableEmotes(ServerPlayer player, String query) {
        return filterPlayableEmotes(getPlayableEmotes(player), query);
    }

    static List<PlayableEmote> filterPlayableEmotes(List<PlayableEmote> emotes, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.copyOf(emotes);
        }

        return emotes.stream()
            .map(emote -> new RankedEmote(emote, searchRank(emote, normalizedQuery)))
            .filter(rankedEmote -> rankedEmote.rank() < Integer.MAX_VALUE)
            .sorted(Comparator.comparingInt(RankedEmote::rank))
            .map(RankedEmote::emote)
            .toList();
    }

    private static int searchRank(PlayableEmote emote, String query) {
        String displayName = emote.displayName().toLowerCase(Locale.ROOT);
        String commandName = emote.commandName().toLowerCase(Locale.ROOT);
        String description = emote.description().toLowerCase(Locale.ROOT);
        if (displayName.equals(query)) return 0;
        if (displayName.startsWith(query)) return 1;
        if (commandName.startsWith(query)) return 2;
        if (displayName.contains(query) || commandName.contains(query) || description.contains(query)) return 3;
        return Integer.MAX_VALUE;
    }

    public List<String> getPlayNames() {
        return collectPlayNames(ignored -> true);
    }

    public List<String> getPlayablePlayNames(ServerPlayer player) {
        return collectPlayNames(definition -> canPlay(player, definition));
    }

    private List<String> collectPlayNames(Predicate<EmoteDefinition> definitionFilter) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
            if (definitionFilter.test(definition)) {
                names.putIfAbsent(definition.commandName(), definition.commandName());
                names.putIfAbsent(definition.namespace(), definition.namespace());
            }
        }
        return List.copyOf(names.values());
    }

    public PlayableEmoteSelection findSelection(ServerPlayer player, String commandName) {
        EmoteDefinition definition = this.emoteRegistry.findDefinitionForPlay(commandName);
        if (definition == null) {
            return PlayableEmoteSelection.failure("Unknown: " + commandName);
        }
        if (!canPlay(player, definition)) {
            return PlayableEmoteSelection.failure("No emote permission.");
        }
        return PlayableEmoteSelection.success(definition);
    }

    private boolean canPlay(ServerPlayer player, EmoteDefinition definition) {
        return this.playPermissionChecker.canPlay(player, definition);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, EmoteDefinition definition);
    }

    private record RankedEmote(PlayableEmote emote, int rank) {
    }
}
