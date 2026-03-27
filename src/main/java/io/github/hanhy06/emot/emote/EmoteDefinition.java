package io.github.hanhy06.emot.emote;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EmoteDefinition(
	String namespace,
	String name,
	String description,
	Path datapackPath,
	int partCount,
	List<EmoteAnimation> animations
) {
	public EmoteDefinition {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(datapackPath, "datapackPath");
		Objects.requireNonNull(animations, "animations");

		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		if (description.isBlank()) {
			throw new IllegalArgumentException("description must not be blank");
		}

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

	public String createDisplayName(String animationName) {
		Objects.requireNonNull(animationName, "animationName");

		if (this.animations.size() <= 1 || "default".equals(animationName)) {
			return this.name;
		}

		return this.name + " - " + animationName;
	}

	public String createDisplayDescription(String animationName) {
		Objects.requireNonNull(animationName, "animationName");

		if (this.animations.size() <= 1 || "default".equals(animationName)) {
			return this.description;
		}

		return this.description + " (" + animationName + ")";
	}
}
