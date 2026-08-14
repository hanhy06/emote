package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedDefinition;
import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class EmoteQueryService {
    private final EmoteCatalog emoteCatalog;
    private final PlayPermissionChecker playPermissionChecker;

    public EmoteQueryService(EmoteCatalog emoteCatalog, PermissionService permissionService) {
        this(emoteCatalog, (player, emote) -> permissionService.canPlay(player, emote.id()));
    }

    EmoteQueryService(EmoteCatalog emoteCatalog, PlayPermissionChecker playPermissionChecker) {
        this.emoteCatalog = emoteCatalog;
        this.playPermissionChecker = playPermissionChecker;
    }

    public List<EmoteSummary> getAll(ServerPlayer player) {
        return this.emoteCatalog.getAllDefinitions().stream()
            .filter(PreparedDefinition::standalone)
            .filter(emote -> canPlay(player, emote))
            .sorted(Comparator.comparing(PreparedDefinition::name).thenComparing(PreparedDefinition::id))
            .map(emote -> new EmoteSummary(emote.id(), emote.name(), emote.description()))
            .toList();
    }

    public List<EmoteSummary> search(ServerPlayer player, String query) {
        return filter(getAll(player), query);
    }

    static List<EmoteSummary> filter(List<EmoteSummary> emotes, String query) {
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

    private static int searchRank(EmoteSummary emote, String query) {
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

    private List<String> collectPlayIds(Predicate<PreparedDefinition> filter) {
        List<String> ids = new java.util.ArrayList<>();
        for (PreparedDefinition emote : this.emoteCatalog.getAllDefinitions()) {
            if (emote.standalone() && filter.test(emote)) {
                ids.add(emote.id());
            }
        }
        return List.copyOf(ids);
    }

    private boolean canPlay(ServerPlayer player, PreparedDefinition emote) {
        return this.playPermissionChecker.canPlay(player, emote);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, PreparedDefinition emote);
    }

    private record RankedEntry(EmoteSummary emote, int rank) {
    }
}
