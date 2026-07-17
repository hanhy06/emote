package io.github.hanhy06.emote.emote;

import java.util.*;

public class EmoteRegistry {
    public static final int MAX_EMOTE_COUNT = 512;

    private volatile RegistryState state = RegistryState.empty();

    public int replace(Collection<RegisteredEmote> emotes) {
        List<RegisteredEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(RegisteredEmote::id));

        Set<String> ids = new HashSet<>();
        for (RegisteredEmote emote : sorted) {
            if (!ids.add(emote.id())) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
        }

        int acceptedCount = Math.min(sorted.size(), MAX_EMOTE_COUNT);
        List<RegisteredEmote> accepted = List.copyOf(sorted.subList(0, acceptedCount));
        LinkedHashMap<String, RegisteredEmote> byId = new LinkedHashMap<>();
        for (RegisteredEmote emote : accepted) {
            byId.put(emote.id(), emote);
        }
        this.state = new RegistryState(Map.copyOf(byId), accepted);
        return sorted.size() - acceptedCount;
    }

    public List<RegisteredEmote> getAll() {
        return this.state.emotes();
    }

    public RegisteredEmote find(String id) {
        return this.state.byId().get(id);
    }

    public int size() {
        return this.state.emotes().size();
    }

    private record RegistryState(Map<String, RegisteredEmote> byId, List<RegisteredEmote> emotes) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of());
        }
    }
}
