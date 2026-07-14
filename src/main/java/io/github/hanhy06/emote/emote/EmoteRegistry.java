package io.github.hanhy06.emote.emote;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EmoteRegistry {
    private volatile RegistryState state = RegistryState.empty();

    public void replaceDefinitions(Collection<EmoteDefinition> definitions) {
        List<EmoteDefinition> sortedDefinitions = new java.util.ArrayList<>(definitions);
        sortedDefinitions.sort(Comparator.comparing(EmoteDefinition::namespace));

        LinkedHashMap<String, EmoteDefinition> definitionMap = new LinkedHashMap<>();
        LinkedHashMap<String, EmoteDefinition> commandDefinitionMap = new LinkedHashMap<>();
        for (EmoteDefinition definition : sortedDefinitions) {
            definitionMap.put(definition.namespace(), definition);
            commandDefinitionMap.putIfAbsent(normalizeKey(definition.commandName()), definition);
        }

        this.state = new RegistryState(
            Map.copyOf(definitionMap),
            Map.copyOf(commandDefinitionMap),
            List.copyOf(definitionMap.values())
        );
    }

    public List<EmoteDefinition> getDefinitions() {
        return this.state.definitions();
    }

    public EmoteDefinition findDefinition(String namespace) {
        return this.state.definitionMap().get(namespace);
    }

    public EmoteDefinition findDefinitionByCommandName(String commandName) {
        return this.state.commandDefinitionMap().get(normalizeKey(commandName));
    }

    public EmoteDefinition findDefinitionForPlay(String commandNameOrNamespace) {
        EmoteDefinition definition = findDefinitionByCommandName(commandNameOrNamespace);
        return definition != null ? definition : findDefinition(commandNameOrNamespace);
    }

    public int size() {
        return this.state.definitionMap().size();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record RegistryState(
        Map<String, EmoteDefinition> definitionMap,
        Map<String, EmoteDefinition> commandDefinitionMap,
        List<EmoteDefinition> definitions
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), List.of());
        }
    }
}
