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
        this(emoteRegistry, (player, definition) -> permissionService.canPlay(player, definition.id()));
    }

    PlayableEmoteService(EmoteRegistry emoteRegistry, PlayPermissionChecker playPermissionChecker) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
    }

    public List<PlayableEmote> getPlayableEmotes(ServerPlayer player) {
        return this.emoteRegistry.getDefinitions().stream()
            .filter(definition -> canPlay(player, definition))
            .sorted(Comparator.comparing(EmoteDefinition::name).thenComparing(EmoteDefinition::id))
            .map(definition -> new PlayableEmote(definition.id(), definition.name(), definition.description()))
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
        String id = emote.id().toLowerCase(Locale.ROOT);
        String description = emote.description().toLowerCase(Locale.ROOT);
        if (displayName.equals(query)) return 0;
        if (displayName.startsWith(query)) return 1;
        if (id.startsWith(query)) return 2;
        if (displayName.contains(query) || id.contains(query) || description.contains(query)) return 3;
        return Integer.MAX_VALUE;
    }

    public List<String> getPlayIds() {
        return collectPlayIds(ignored -> true);
    }

    public List<String> getPlayablePlayIds(ServerPlayer player) {
        return collectPlayIds(definition -> canPlay(player, definition));
    }

    private List<String> collectPlayIds(Predicate<EmoteDefinition> definitionFilter) {
        List<String> ids = new java.util.ArrayList<>();
        for (EmoteDefinition definition : this.emoteRegistry.getDefinitions()) {
            if (definitionFilter.test(definition)) {
                ids.add(definition.id());
            }
        }
        return List.copyOf(ids);
    }

    public PlayableEmoteSelection findSelection(ServerPlayer player, String id) {
        EmoteDefinition definition = this.emoteRegistry.findDefinition(id);
        if (definition == null) {
            return PlayableEmoteSelection.failure("Unknown: " + id);
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
