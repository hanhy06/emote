package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.IdentifierConfig;
import io.github.hanhy06.emote.config.data.IdentifierEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionServiceTest {
	@Test
	void onIdentifierConfigReloadBuildsNamespacePermissionMap() {
		PermissionService service = new PermissionService();
		service.onIdentifierConfigReload(new IdentifierConfig(
			new LinkedHashMap<>(java.util.Map.of(
				"",
				List.of(new IdentifierEntry("wave_pack", "Wave", "wave", "Friendly wave", "default")),
				"emote.pack.vip",
				List.of(new IdentifierEntry("bow_pack", "Bow", "bow", "Polite bow", "default"))
			))
		));

		assertEquals("", service.findNamespacePermission("wave_pack"));
		assertEquals("emote.pack.vip", service.findNamespacePermission("bow_pack"));
		assertEquals("", service.findNamespacePermission("missing_pack"));
	}
}
