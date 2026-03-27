package io.github.hanhy06.emot.emote;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EmoteDefinition(String namespace, Path datapackPath, int partCount, List<EmoteAnimation> animations) {
	public EmoteDefinition {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(datapackPath, "datapackPath");
		Objects.requireNonNull(animations, "animations");

		if (partCount < 0) {
			throw new IllegalArgumentException("partCount must be zero or greater");
		}

		animations = List.copyOf(animations);
	}

	public Optional<EmoteAnimation> findAnimation(String animationName) {
		return this.animations.stream()
			.filter(animation -> animation.name().equals(animationName))
			.findFirst();
	}

	public Optional<EmoteAnimation> findDefaultAnimation() {
		return findAnimation("default").or(() -> this.animations.stream().findFirst());
	}
}
