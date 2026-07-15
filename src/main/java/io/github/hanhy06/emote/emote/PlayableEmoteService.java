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
        this(emoteRegistry, (player, emote) -> permissionService.canPlay(player, emote.id()));
    }

    PlayableEmoteService(EmoteRegistry emoteRegistry, PlayPermissionChecker playPermissionChecker) {
        this.emoteRegistry = emoteRegistry;
        this.playPermissionChecker = playPermissionChecker;
    }

    public List<PlayableEmote> getPlayableEmotes(ServerPlayer player) {
        return this.emoteRegistry.getAll().stream()
            .filter(emote -> canPlay(player, emote))
            .sorted(Comparator.comparing(RegisteredEmote::name).thenComparing(RegisteredEmote::id))
            .map(emote -> new PlayableEmote(emote.id(), emote.name(), emote.description()))
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
        return collectPlayIds(emote -> canPlay(player, emote));
    }

    private List<String> collectPlayIds(Predicate<RegisteredEmote> filter) {
        List<String> ids = new java.util.ArrayList<>();
        for (RegisteredEmote emote : this.emoteRegistry.getAll()) {
            if (filter.test(emote)) {
                ids.add(emote.id());
            }
        }
        return List.copyOf(ids);
    }

    public PlayableEmoteSelection findSelection(ServerPlayer player, String id) {
        RegisteredEmote emote = this.emoteRegistry.find(id);
        if (emote == null) {
            return PlayableEmoteSelection.failure("Unknown: " + id);
        }
        if (!canPlay(player, emote)) {
            return PlayableEmoteSelection.failure("No emote permission.");
        }
        return PlayableEmoteSelection.success(emote);
    }

    private boolean canPlay(ServerPlayer player, RegisteredEmote emote) {
        return this.playPermissionChecker.canPlay(player, emote);
    }

    @FunctionalInterface
    interface PlayPermissionChecker {
        boolean canPlay(ServerPlayer player, RegisteredEmote emote);
    }

    private record RankedEmote(PlayableEmote emote, int rank) {
    }
}
