package io.github.hanhy06.emote.skin;

import java.util.Objects;

public record PlayerSkinHost(
	String host,
	int port
) {
	public PlayerSkinHost {
		Objects.requireNonNull(host, "host");
		if (host.isBlank()) {
			throw new IllegalArgumentException("host must not be blank");
		}

		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port must be between 1 and 65535");
		}
	}

	public String createBaseUrl() {
		return "http://" + normalizeHostForUrl(this.host) + ":" + this.port;
	}

	private static String normalizeHostForUrl(String host) {
		if (host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")) {
			return "[" + host + "]";
		}

		return host;
	}
}
