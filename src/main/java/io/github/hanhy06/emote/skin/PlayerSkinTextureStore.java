package io.github.hanhy06.emote.skin;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlayerSkinTextureStore {
	private final ConcurrentMap<String, byte[]> textureMap = new ConcurrentHashMap<>();

	public void put(String token, byte[] pngBytes) {
		this.textureMap.put(token, pngBytes);
	}

	public Optional<byte[]> find(String token) {
		return Optional.ofNullable(this.textureMap.get(token));
	}

	public void clear() {
		this.textureMap.clear();
	}
}
