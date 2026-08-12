package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class PlayableEmoteService {
    private final EmoteRegistry emoteRegistry;
    private final PlayPermissionChecker playPermissionChecker;

    public PlayableEmoteService(EmoteRegistry emoteRegistry, PermissionService permissionService) {
        this(emoteRegistry, (player, emote) -> permissionService.canPlay(player, emote.id()));
    }

    PlayableEmoteService(EmoteRegistry emoteRegistry, PlayPermissionChecker playPermissionChecker) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
    }

    public List<PlayableEmote> getAll(ServerPlayer player) {
        return this.emoteRegistry.getAllDefinitions().stream()
            .filter(emote -> canPlay(player, emote))
            .sorted(Comparator.comparing(EmoteDefinition::name).thenComparing(EmoteDefinition::id))
            .map(emote -> new PlayableEmote(emote.id(), emote.name(), emote.description()))
            .toList();
    }

    public List<PlayableEmote> search(ServerPlayer player, String query) {
        return filter(getAll(player), query);
    }

    static List<PlayableEmote> filter(List<PlayableEmote> emotes, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.copyOf(emotes);
        }

        return emotes.stream()
            .map(emote -> new RankedEntry(emote, searchRank(emote, normalizedQuery)))
            .filter(rankedEntry -> rankedEntry.rank() < Integer.MAX_VALUE)
            .sorted(Comparator.comparingInt(RankedEntry::rank))
            .map(RankedEntry::emote)
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

    public List<String> getAllIds() {
        return collectPlayIds(ignored -> true);
    }

    public List<String> getPlayableIds(ServerPlayer player) {
        return collectPlayIds(emote -> canPlay(player, emote));
    }

    private List<String> collectPlayIds(Predicate<EmoteDefinition> filter) {
        List<String> ids = new java.util.ArrayList<>();
        for (EmoteDefinition emote : this.emoteRegistry.getAllDefinitions()) {
            if (filter.test(emote)) {
                ids.add(emote.id());
            }
        }
        return List.copyOf(ids);
    }

    private boolean canPlay(ServerPlayer player, EmoteDefinition emote) {
        return this.playPermissionChecker.canPlay(player, emote);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, EmoteDefinition emote);
    }

    private record RankedEntry(PlayableEmote emote, int rank) {
    }
}
