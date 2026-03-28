package io.github.hanhy06.emote.skin;

import net.minecraft.network.Connection;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public class PlayerSkinHostStore {
	private final Map<Connection, PlayerSkinHost> hostMap = Collections.synchronizedMap(new WeakHashMap<>());

	public void remember(Connection connection, String host, int port) {
		if (connection == null) {
			return;
		}

		String normalizedHost = normalizeHost(host);
		if (normalizedHost == null) {
			return;
		}

		this.hostMap.put(connection, new PlayerSkinHost(normalizedHost, port));
	}

	public Optional<PlayerSkinHost> find(Connection connection) {
		if (connection == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(this.hostMap.get(connection));
	}

	public void clear() {
		this.hostMap.clear();
	}

	private String normalizeHost(String host) {
		if (host == null) {
			return null;
		}

		String normalizedHost = host;
		int forwardingSeparatorIndex = normalizedHost.indexOf('\0');
		if (forwardingSeparatorIndex >= 0) {
			normalizedHost = normalizedHost.substring(0, forwardingSeparatorIndex);
		}

		normalizedHost = normalizedHost.trim();
		return normalizedHost.isEmpty() ? null : normalizedHost;
	}
}
