package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.util.EmoteTags;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
            .map(emote -> new EmoteSummary(emote.id(), emote.name(), emote.description(), emote.tags()))
            .toList();
    }

    public List<EmoteSummary> search(ServerPlayer player, String query) {
        return filter(getAll(player), query);
    }

    static List<EmoteSummary> filter(List<EmoteSummary> emotes, String query) {
        SearchQuery searchQuery = parseQuery(query);
        if (searchQuery.isEmpty()) {
            return List.copyOf(emotes);
        }

        return emotes.stream()
            .map(emote -> new RankedEntry(emote, searchRank(emote, searchQuery)))
            .filter(rankedEntry -> rankedEntry.rank() < Integer.MAX_VALUE)
            .sorted(Comparator.comparingInt(RankedEntry::rank))
            .map(RankedEntry::emote)
            .toList();
    }

    public static List<EmoteSummary> filterPreservingOrder(List<EmoteSummary> emotes, String query) {
        SearchQuery searchQuery = parseQuery(query);
        if (searchQuery.isEmpty()) {
            return List.copyOf(emotes);
        }

        return emotes.stream()
            .filter(emote -> searchRank(emote, searchQuery) < Integer.MAX_VALUE)
            .toList();
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

    private static int searchRank(EmoteSummary emote, SearchQuery query) {
        if (!emote.tags().containsAll(query.tags())) {
            return Integer.MAX_VALUE;
        }
        if (query.text().isEmpty()) {
            return 0;
        }

        String displayName = emote.displayName().toLowerCase(Locale.ROOT);
        String id = emote.id().toLowerCase(Locale.ROOT);
        String description = emote.description().toLowerCase(Locale.ROOT);
        if (displayName.equals(query.text())) return 0;
        if (displayName.startsWith(query.text())) return 1;
        if (id.startsWith(query.text())) return 2;
        if (displayName.contains(query.text()) || id.contains(query.text()) || description.contains(query.text())) return 3;
        return Integer.MAX_VALUE;
    }

    private static SearchQuery parseQuery(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new SearchQuery("", List.of());
        }

        List<String> textParts = new ArrayList<>();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String part : normalized.split("\\s+")) {
            List<String> parsedTags = EmoteTags.extract(part);
            if (parsedTags.size() == 1 && part.equals("#" + parsedTags.getFirst())) {
                tags.add(parsedTags.getFirst());
            } else {
                textParts.add(part);
            }
        }
        return new SearchQuery(String.join(" ", textParts), List.copyOf(tags));
    }

    private record SearchQuery(String text, List<String> tags) {
        private boolean isEmpty() {
            return this.text.isEmpty() && this.tags.isEmpty();
        }
    }

    private record RankedEntry(EmoteSummary emote, int rank) {
    }

    @FunctionalInterface
    interface VisibilityChecker {
        boolean isVisible(ServerPlayer player, PlayableEmote emote);
    }
}
