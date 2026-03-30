package io.github.hanhy06.emote.skin;

public record PlayerSkinSegment(int startY, int endY) {
	public static final int SIDE_FACE_HEIGHT = 12;
	public static final PlayerSkinSegment FULL = new PlayerSkinSegment(0, SIDE_FACE_HEIGHT);

	public PlayerSkinSegment {
		if (startY < 0 || startY >= SIDE_FACE_HEIGHT) {
			throw new IllegalArgumentException("startY must be between 0 and " + (SIDE_FACE_HEIGHT - 1));
		}

		if (endY <= startY || endY > SIDE_FACE_HEIGHT) {
			throw new IllegalArgumentException("endY must be between " + (startY + 1) + " and " + SIDE_FACE_HEIGHT);
		}
	}

	public String id() {
		return this.startY + "-" + this.endY;
	}
}
