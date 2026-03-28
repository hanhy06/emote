package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.EmotePack;
import io.github.hanhy06.emote.config.PackConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmotePermissionServiceTest {
	@Test
	void onPackConfigReloadBuildsNamespacePermissionMap() {
		EmotePermissionService service = new EmotePermissionService();
		service.onPackConfigReload(new PackConfig(
			PackConfig.createDefault().version(),
			new LinkedHashMap<>(java.util.Map.of(
				"",
				List.of(new EmotePack("wave_pack", "Wave", "wave", "Friendly wave", "default")),
				"emote.pack.vip",
				List.of(new EmotePack("bow_pack", "Bow", "bow", "Polite bow", "default"))
			))
		));

		assertEquals("", service.findDatapackPermission("wave_pack"));
		assertEquals("emote.pack.vip", service.findDatapackPermission("bow_pack"));
		assertEquals("", service.findDatapackPermission("missing_pack"));
	}
}
