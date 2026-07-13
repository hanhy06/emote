package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.PackConfig;
import io.github.hanhy06.emote.config.data.PackOverride;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionServiceTest {
	@Test
	void onPackConfigReloadBuildsNamespacePermissionMap() {
		PermissionService service = new PermissionService();
		service.onPackConfigReload(new PackConfig(
			new LinkedHashMap<>(java.util.Map.of(
				"wave_pack", new PackOverride(true, ""),
				"bow_pack", new PackOverride(true, "emote.pack.vip")
			))
		));

		assertEquals("", service.findNamespacePermission("wave_pack"));
		assertEquals("emote.pack.vip", service.findNamespacePermission("bow_pack"));
		assertEquals("", service.findNamespacePermission("missing_pack"));
	}
}
