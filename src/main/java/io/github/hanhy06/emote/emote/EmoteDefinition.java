package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.skin.EmoteSkinPart;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EmoteDefinition(
	String namespace,
	String name,
	String description,
	String commandName,
	String defaultAnimationName,
	Path datapackPath,
	int partCount,
	List<EmoteAnimation> animations,
	List<EmoteSkinPart> skinParts
) {
	public EmoteDefinition {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(commandName, "commandName");
		Objects.requireNonNull(defaultAnimationName, "defaultAnimationName");
		Objects.requireNonNull(datapackPath, "datapackPath");
		Objects.requireNonNull(animations, "animations");
		Objects.requireNonNull(skinParts, "skinParts");

		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		if (description.isBlank()) {
			throw new IllegalArgumentException("description must not be blank");
		}

		if (commandName.isBlank()) {
			throw new IllegalArgumentException("commandName must not be blank");
		}

		if (defaultAnimationName.isBlank()) {
			throw new IllegalArgumentException("defaultAnimationName must not be blank");
		}

		if (partCount < 0) {
			throw new IllegalArgumentException("partCount must be zero or greater");
		}

		animations = List.copyOf(animations);
		skinParts = List.copyOf(skinParts);
	}

	public Optional<EmoteAnimation> findAnimation(String animationName) {
		for (EmoteAnimation animation : this.animations) {
			if (animation.name().equals(animationName)) {
				return Optional.of(animation);
			}
		}

		return Optional.empty();
	}

	public Optional<EmoteAnimation> findDefaultAnimation() {
		EmoteAnimation fallbackAnimation = null;
		for (EmoteAnimation animation : this.animations) {
			if (animation.name().equals(this.defaultAnimationName)) {
				return Optional.of(animation);
			}

			if (fallbackAnimation == null && animation.name().equals("default")) {
				fallbackAnimation = animation;
			}
		}

		if (fallbackAnimation != null) {
			return Optional.of(fallbackAnimation);
		}

		return this.animations.isEmpty()
			? Optional.empty()
			: Optional.of(this.animations.get(0));
	}

	public boolean isDefaultAnimation(String animationName) {
		Objects.requireNonNull(animationName, "animationName");
		return findDefaultAnimation()
			.map(animation -> animation.name().equals(animationName))
			.orElse(false);
	}

	public String createDisplayName(String animationName) {
		Objects.requireNonNull(animationName, "animationName");

		if (this.animations.size() <= 1 || isDefaultAnimation(animationName)) {
			return this.name;
		}

		return this.name + " - " + animationName;
	}

	public String createDisplayDescription(String animationName) {
		Objects.requireNonNull(animationName, "animationName");

		if (this.animations.size() <= 1 || isDefaultAnimation(animationName)) {
			return this.description;
		}

		return this.description + " (" + animationName + ")";
	}
}
