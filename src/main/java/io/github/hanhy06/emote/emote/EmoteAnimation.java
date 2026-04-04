package io.github.hanhy06.emote.emote;

import java.util.Objects;

public record EmoteAnimation(
	String name,
	String datapackAnimationName,
	String displayName,
	String playFunctionName,
	int keyframeCount,
	boolean loop
) {
	private static final String DEFAULT_PLAY_FUNCTION_NAME = "play_anim";
	private static final String LOOP_PLAY_FUNCTION_NAME = "play_anim_loop";

	public EmoteAnimation(String name, int keyframeCount) {
		this(name, name, name, DEFAULT_PLAY_FUNCTION_NAME, keyframeCount, false);
	}

	public EmoteAnimation {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(datapackAnimationName, "datapackAnimationName");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(playFunctionName, "playFunctionName");

		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		if (datapackAnimationName.isBlank()) {
			throw new IllegalArgumentException("datapackAnimationName must not be blank");
		}

		if (displayName.isBlank()) {
			throw new IllegalArgumentException("displayName must not be blank");
		}

		if (playFunctionName.isBlank()) {
			throw new IllegalArgumentException("playFunctionName must not be blank");
		}

		if (keyframeCount < 0) {
			throw new IllegalArgumentException("keyframeCount must be zero or greater");
		}
	}

	public static EmoteAnimation createLoop(String animationName, int keyframeCount) {
		Objects.requireNonNull(animationName, "animationName");
		return new EmoteAnimation(
			animationName + "_loop",
			animationName,
			animationName + " loop",
			LOOP_PLAY_FUNCTION_NAME,
			keyframeCount,
			true
		);
	}
}
