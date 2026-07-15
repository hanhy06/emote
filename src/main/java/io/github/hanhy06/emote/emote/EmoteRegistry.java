package io.github.hanhy06.emote.emote;

import java.util.*;

public class EmoteRegistry {
    private volatile RegistryState state = RegistryState.empty();

    public void replace(Collection<RegisteredEmote> emotes) {
        List<RegisteredEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(RegisteredEmote::id));

        LinkedHashMap<String, RegisteredEmote> byId = new LinkedHashMap<>();
        for (RegisteredEmote emote : sorted) {
            if (byId.putIfAbsent(emote.id(), emote) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
        }
        this.state = new RegistryState(Map.copyOf(byId), List.copyOf(sorted));
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
