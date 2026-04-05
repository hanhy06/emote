package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.skin.EmoteSkinPart;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record EmoteDefinition(
	String namespace,
	String name,
	String description,
	String commandName,
	String defaultAnimationName,
	String options,
	Path datapackPath,
	int partCount,
	List<EmoteAnimation> animations,
	List<EmoteSkinPart> skinParts
) {
	public EmoteDefinition(
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
		this(namespace, name, description, commandName, defaultAnimationName, "", datapackPath, partCount, animations, skinParts);
	}

	public EmoteDefinition {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(commandName, "commandName");
		Objects.requireNonNull(defaultAnimationName, "defaultAnimationName");
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(datapackPath, "datapackPath");
		Objects.requireNonNull(animations, "animations");
		Objects.requireNonNull(skinParts, "skinParts");

		options = EmoteOptions.normalize(options);

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

	public EmoteAnimation findAnimation(String animationName) {
		for (EmoteAnimation animation : this.animations) {
			if (animation.name().equals(animationName)) {
				return animation;
			}
		}

		return null;
	}

	public EmoteAnimation findDefaultAnimation() {
		EmoteAnimation fallbackAnimation = null;
		for (EmoteAnimation animation : this.animations) {
			if (animation.name().equals(this.defaultAnimationName)) {
				return animation;
			}

			if (fallbackAnimation == null && animation.name().equals("default")) {
				fallbackAnimation = animation;
			}
		}

		if (fallbackAnimation != null) {
			return fallbackAnimation;
		}

		return this.animations.isEmpty() ? null : this.animations.getFirst();
	}

	public boolean isDefaultAnimation(String animationName) {
		Objects.requireNonNull(animationName, "animationName");
		EmoteAnimation defaultAnimation = findDefaultAnimation();
		return defaultAnimation != null && defaultAnimation.name().equals(animationName);
	}

	public EmoteOptions parsedOptions() {
		return EmoteOptions.parse(this.options);
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
