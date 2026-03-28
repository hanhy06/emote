package io.github.hanhy06.emote.skin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlayerSkinTextureStore {
	private final ConcurrentMap<String, byte[]> textureMap = new ConcurrentHashMap<>();

	public void put(String token, byte[] pngBytes) {
		this.textureMap.put(token, pngBytes);
	}

	public byte[] find(String token) {
		return this.textureMap.get(token);
	}

	public void clear() {
		this.textureMap.clear();
	}
}
