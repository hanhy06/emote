package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record PreparedPlayerSkin(
		String textureHash,
		boolean slimModel
) {
	public PreparedPlayerSkin {
		Objects.requireNonNull(textureHash, "textureHash");
	}
}
