package io.github.hanhy06.emote.emote;

import java.util.*;

public class EmoteRegistry {
    private volatile RegistryState state = RegistryState.empty();

    public void replaceDefinitions(Collection<EmoteDefinition> definitions) {
        List<EmoteDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparing(EmoteDefinition::id));

        LinkedHashMap<String, EmoteDefinition> byId = new LinkedHashMap<>();
        for (EmoteDefinition definition : sorted) {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + definition.id());
            }
        }
        this.state = new RegistryState(Map.copyOf(byId), List.copyOf(sorted));
    }

    public List<EmoteDefinition> getDefinitions() {
        return this.state.definitions();
    }

    public EmoteDefinition findDefinition(String id) {
        return this.state.byId().get(id);
    }

    public int size() {
        return this.state.definitions().size();
    }

    private record RegistryState(Map<String, EmoteDefinition> byId, List<EmoteDefinition> definitions) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of());
        }
    }
}
