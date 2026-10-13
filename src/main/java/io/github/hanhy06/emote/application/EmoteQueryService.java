package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.util.EmoteTags;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import io.github.hanhy06.emote.content.PreparedEmote;

public class EmoteQueryService {
    private final EmoteCatalog emoteCatalog;
    private final EmotePlayService playService;

    public EmoteQueryService(EmoteCatalog emoteCatalog, EmotePlayService playService) {
        this.emoteCatalog = emoteCatalog;
        this.playService = playService;
    }

    public List<EmoteSummary> getAll(ServerPlayer player) {
        return this.emoteCatalog.emotes().stream()
            .filter(emote -> this.playService.isVisibleForCommand(player, emote))
            .sorted(Comparator.comparing(PreparedEmote::name).thenComparing(PreparedEmote::id))
            .map(emote -> new EmoteSummary(emote.id(), emote.name(), emote.description(), emote.tags()))
            .toList();
    }

    public List<EmoteSummary> search(ServerPlayer player, String query) {
        List<EmoteSummary> emotes = getAll(player);
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
        return this.emoteCatalog.emotes().stream().filter(PreparedEmote::standalone).map(PreparedEmote::id).toList();
    }

    public List<String> getPlayableIds(ServerPlayer player) {
        return this.emoteCatalog.emotes().stream()
            .filter(emote -> this.playService.isVisibleForCommand(player, emote))
            .map(PreparedEmote::id)
            .toList();
    }

    private static int searchRank(EmoteSummary emote, SearchQuery query) {
        if (!emote.tags().containsAll(query.tags())) {
            return Integer.MAX_VALUE;
        }
        if (query.text().isEmpty()) {
            return 0;
        }

        String name = emote.name().toLowerCase(Locale.ROOT);
        String id = emote.id().toLowerCase(Locale.ROOT);
        String description = emote.description().toLowerCase(Locale.ROOT);
        if (name.equals(query.text())) return 0;
        if (name.startsWith(query.text())) return 1;
        if (id.startsWith(query.text())) return 2;
        if (name.contains(query.text()) || id.contains(query.text()) || description.contains(query.text())) return 3;
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
