package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.util.EmoteTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class EmoteSearch {
    private EmoteSearch() {
    }

    public static List<EmoteSummary> filterAndRank(List<EmoteSummary> emotes, String query) {
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
}
