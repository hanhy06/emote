package io.github.hanhy06.emote.emote;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EmoteRegistry {
	private Map<String, EmoteDefinition> definitionMap = Map.of();
	private Map<String, EmoteDefinition> commandDefinitionMap = Map.of();

	public synchronized void replaceDefinitions(Collection<EmoteDefinition> definitions) {
		LinkedHashMap<String, EmoteDefinition> definitionMap = new LinkedHashMap<>();
		LinkedHashMap<String, EmoteDefinition> commandDefinitionMap = new LinkedHashMap<>();

		definitions.stream()
			.sorted(Comparator.comparing(EmoteDefinition::namespace))
			.forEach(definition -> {
				definitionMap.put(definition.namespace(), definition);
				commandDefinitionMap.putIfAbsent(normalizeKey(definition.commandName()), definition);
			});

		this.definitionMap = Collections.unmodifiableMap(definitionMap);
		this.commandDefinitionMap = Collections.unmodifiableMap(commandDefinitionMap);
	}

	public synchronized void clearDefinitions() {
		this.definitionMap = Map.of();
		this.commandDefinitionMap = Map.of();
	}

	public synchronized List<EmoteDefinition> getDefinitions() {
		return List.copyOf(this.definitionMap.values());
	}

	public synchronized Optional<EmoteDefinition> findDefinition(String namespace) {
		return Optional.ofNullable(this.definitionMap.get(namespace));
	}

	public synchronized Optional<EmoteDefinition> findDefinitionByCommandName(String commandName) {
		return Optional.ofNullable(this.commandDefinitionMap.get(normalizeKey(commandName)));
	}

	public synchronized Optional<EmoteDefinition> findDefinitionForPlay(String commandNameOrNamespace) {
		return findDefinitionByCommandName(commandNameOrNamespace)
			.or(() -> findDefinition(commandNameOrNamespace));
	}

	public synchronized Optional<EmoteAnimation> findAnimation(String namespace, String animationName) {
		return findDefinition(namespace).flatMap(definition -> definition.findAnimation(animationName));
	}

	public synchronized List<String> getNamespaces() {
		return List.copyOf(this.definitionMap.keySet());
	}

	public synchronized List<String> getPlayNames() {
		LinkedHashMap<String, String> playNameMap = new LinkedHashMap<>();
		for (EmoteDefinition definition : this.definitionMap.values()) {
			playNameMap.putIfAbsent(definition.commandName(), definition.commandName());
			playNameMap.putIfAbsent(definition.namespace(), definition.namespace());
		}

		return List.copyOf(playNameMap.values());
	}

	public synchronized List<String> getAnimationNames(String namespace) {
		return findDefinition(namespace)
			.map(definition -> definition.animations().stream().map(EmoteAnimation::name).toList())
			.orElseGet(List::of);
	}

	public synchronized List<String> getAnimationNamesForPlay(String commandNameOrNamespace) {
		return findDefinitionForPlay(commandNameOrNamespace)
			.map(definition -> definition.animations().stream().map(EmoteAnimation::name).toList())
			.orElseGet(List::of);
	}

	public synchronized int size() {
		return this.definitionMap.size();
	}

	private String normalizeKey(String value) {
		return value == null ? "" : value.trim().toLowerCase();
	}
}
