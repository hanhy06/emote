package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class EmoteQueryService {
    private final EmoteCatalog emoteCatalog;
    private final VisibilityChecker visibilityChecker;

    public EmoteQueryService(EmoteCatalog emoteCatalog, PlaybackPolicyService playbackPolicy) {
        this(emoteCatalog, playbackPolicy::isVisibleForCommand);
    }

    EmoteQueryService(EmoteCatalog emoteCatalog, VisibilityChecker visibilityChecker) {
        this.emoteCatalog = emoteCatalog;
        this.visibilityChecker = visibilityChecker;
    }

    public List<EmoteSummary> getAll(ServerPlayer player) {
        return this.emoteCatalog.emotes().stream()
            .filter(emote -> isVisible(player, emote))
            .sorted(Comparator.comparing(PlayableEmote::name).thenComparing(PlayableEmote::id))
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
        return collectPlayIds(PlayableEmote::standalone);
    }

    public List<String> getPlayableIds(ServerPlayer player) {
        return collectPlayIds(emote -> isVisible(player, emote));
    }

    private List<String> collectPlayIds(Predicate<PlayableEmote> filter) {
        List<String> ids = new java.util.ArrayList<>();
        for (PlayableEmote emote : this.emoteCatalog.emotes()) {
            if (filter.test(emote)) {
                ids.add(emote.id());
            }
        }
        return List.copyOf(ids);
    }

    private boolean isVisible(ServerPlayer player, PlayableEmote emote) {
        return this.visibilityChecker.isVisible(player, emote);
    }

    @FunctionalInterface
    interface VisibilityChecker {
        boolean isVisible(ServerPlayer player, PlayableEmote emote);
    }

    private record RankedEntry(EmoteSummary emote, int rank) {
    }
}
