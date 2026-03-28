package io.github.hanhy06.emote.skin;

public enum PlayerSkinSegment {
	FULL("full"),
	UPPER("upper"),
	LOWER("lower");

	private final String id;

	PlayerSkinSegment(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}
}
