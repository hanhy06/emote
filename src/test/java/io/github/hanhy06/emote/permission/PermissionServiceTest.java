package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.PackConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void defaultAllowsOnlyConfiguredNamespaces() {
        PermissionService service = new PermissionService();
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of("default", List.of("wave_pack"))
        ));

        assertTrue(service.isDefaultAllowed("wave_pack"));
        assertFalse(service.isDefaultAllowed("bow_pack"));
    }

    @Test
    void wildcardAllowsEveryNamespaceForPermission() {
        PermissionService service = new PermissionService();
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of(
                "default", List.of(),
                "emote.pack.vip", List.of("bow_pack"),
                "emote.pack.admin", List.of("*")
            )
        ));

        assertEquals(Set.of("emote.pack.vip", "emote.pack.admin"), Set.copyOf(service.findPermissions("bow_pack")));
        assertEquals(List.of("emote.pack.admin"), service.findPermissions("missing_pack"));
    }

    @Test
    void emptyPermissionsDenyEveryNamespace() {
        PermissionService service = new PermissionService();
        service.onPackConfigReload(new PackConfig(List.of(), Map.of()));

        assertFalse(service.isDefaultAllowed("wave_pack"));
        assertTrue(service.findPermissions("wave_pack").isEmpty());
    }

    @Test
    void defaultConfigAllowsEveryNamespace() {
        PermissionService service = new PermissionService();
        service.onPackConfigReload(PackConfig.createDefault());

        assertTrue(service.isDefaultAllowed("wave_pack"));
        assertTrue(service.canPlay(null, "wave_pack"));
    }

    @Test
    void canPlayCombinesDefaultAndGrantedPermissionGroups() {
        PermissionService service = new PermissionService((ignored, permission) -> permission.equals("emote.pack.vip"));
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of(
                "default", List.of("wave_pack"),
                "emote.pack.vip", List.of("bow_pack"),
                "emote.pack.admin", List.of("*")
            )
        ));

        assertTrue(service.canPlay(null, "wave_pack"));
        assertTrue(service.canPlay(null, "bow_pack"));
        assertFalse(service.canPlay(null, "missing_pack"));
    }

    @Test
    void grantedWildcardPermissionAllowsEveryNamespace() {
        PermissionService service = new PermissionService((ignored, permission) -> permission.equals("emote.pack.admin"));
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of("emote.pack.admin", List.of("*"))
        ));

        assertTrue(service.canPlay(null, "missing_pack"));
    }
}
