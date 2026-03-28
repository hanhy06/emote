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
		LinkedHashMap<String, EmoteDefinition> definitionMap = new LinkedHashMap<>();
		LinkedHashMap<String, EmoteDefinition> commandDefinitionMap = new LinkedHashMap<>();

		definitions.stream()
			.sorted(Comparator.comparing(EmoteDefinition::namespace))
			.forEach(definition -> {
				definitionMap.put(definition.namespace(), definition);
				commandDefinitionMap.putIfAbsent(normalizeKey(definition.commandName()), definition);
			});

		List<EmoteDefinition> definitionList = List.copyOf(definitionMap.values());
		LinkedHashMap<String, String> playNameMap = new LinkedHashMap<>();
		for (EmoteDefinition definition : definitionList) {
			playNameMap.putIfAbsent(definition.commandName(), definition.commandName());
			playNameMap.putIfAbsent(definition.namespace(), definition.namespace());
		}

		this.state = new RegistryState(
			Map.copyOf(definitionMap),
			Map.copyOf(commandDefinitionMap),
			definitionList,
			List.copyOf(definitionMap.keySet()),
			List.copyOf(playNameMap.values())
		);
	}

	public void clearDefinitions() {
		this.state = RegistryState.empty();
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

	public EmoteAnimation findAnimation(String namespace, String animationName) {
		EmoteDefinition definition = findDefinition(namespace);
		return definition == null ? null : definition.findAnimation(animationName);
	}

	public List<String> getNamespaces() {
		return this.state.namespaces();
	}

	public List<String> getPlayNames() {
		return this.state.playNames();
	}

	public List<String> getAnimationNames(String namespace) {
		EmoteDefinition definition = findDefinition(namespace);
		return definition == null
			? List.of()
			: definition.animations().stream().map(EmoteAnimation::name).toList();
	}

	public List<String> getAnimationNamesForPlay(String commandNameOrNamespace) {
		EmoteDefinition definition = findDefinitionForPlay(commandNameOrNamespace);
		return definition == null
			? List.of()
			: definition.animations().stream().map(EmoteAnimation::name).toList();
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
		List<EmoteDefinition> definitions,
		List<String> namespaces,
		List<String> playNames
	) {
		private static RegistryState empty() {
			return new RegistryState(Map.of(), Map.of(), List.of(), List.of(), List.of());
		}
	}
}
