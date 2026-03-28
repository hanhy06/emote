package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerSkinHostTest {
	@Test
	void createBaseUrlWrapsIpv6Hosts() {
		PlayerSkinHost host = new PlayerSkinHost("2001:db8::1", 24454);

		assertEquals("http://[2001:db8::1]:24454", host.createBaseUrl());
	}

	@Test
	void createBaseUrlKeepsDnsHosts() {
		PlayerSkinHost host = new PlayerSkinHost("localhost", 24454);

		assertEquals("http://localhost:24454", host.createBaseUrl());
	}
}
