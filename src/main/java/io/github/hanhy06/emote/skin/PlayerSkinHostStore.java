package io.github.hanhy06.emote.skin;

import net.minecraft.network.Connection;

import java.util.Collections;
import java.util.Map;
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

		try {
			this.hostMap.put(connection, new PlayerSkinHost(normalizedHost, port));
		} catch (IllegalArgumentException ignored) {
		}
	}

	public PlayerSkinHost find(Connection connection) {
		if (connection == null) {
			return null;
		}

		return this.hostMap.get(connection);
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
		if (normalizedHost.startsWith("[")) {
			int closingBracketIndex = normalizedHost.indexOf(']');
			if (closingBracketIndex > 1) {
				normalizedHost = normalizedHost.substring(1, closingBracketIndex);
			}
		} else {
			int firstColonIndex = normalizedHost.indexOf(':');
			if (firstColonIndex >= 0 && normalizedHost.indexOf(':', firstColonIndex + 1) < 0) {
				String portSuffix = normalizedHost.substring(firstColonIndex + 1);
				if (!portSuffix.isBlank() && portSuffix.chars().allMatch(Character::isDigit)) {
					normalizedHost = normalizedHost.substring(0, firstColonIndex);
				}
			}
		}

		normalizedHost = normalizedHost.trim();
		return normalizedHost.isEmpty() ? null : normalizedHost;
	}
}
