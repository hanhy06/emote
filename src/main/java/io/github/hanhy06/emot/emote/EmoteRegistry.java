package io.github.hanhy06.emot.emote;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EmoteRegistry {
	private Map<String, EmoteDefinition> definitionMap = Map.of();

	public synchronized void replaceDefinitions(Collection<EmoteDefinition> definitions) {
		LinkedHashMap<String, EmoteDefinition> definitionMap = new LinkedHashMap<>();

		definitions.stream()
			.sorted(Comparator.comparing(EmoteDefinition::namespace))
			.forEach(definition -> definitionMap.put(definition.namespace(), definition));

		this.definitionMap = Collections.unmodifiableMap(definitionMap);
	}

	public synchronized void clearDefinitions() {
		this.definitionMap = Map.of();
	}

	public synchronized List<EmoteDefinition> getDefinitions() {
		return List.copyOf(this.definitionMap.values());
	}

	public synchronized Optional<EmoteDefinition> findDefinition(String namespace) {
		return Optional.ofNullable(this.definitionMap.get(namespace));
	}

	public synchronized Optional<EmoteAnimation> findAnimation(String namespace, String animationName) {
		return findDefinition(namespace).flatMap(definition -> definition.findAnimation(animationName));
	}

	public synchronized List<String> getNamespaces() {
		return List.copyOf(this.definitionMap.keySet());
	}

	public synchronized List<String> getAnimationNames(String namespace) {
		return findDefinition(namespace)
			.map(definition -> definition.animations().stream().map(EmoteAnimation::name).toList())
			.orElseGet(List::of);
	}

	public synchronized int size() {
		return this.definitionMap.size();
	}
}
