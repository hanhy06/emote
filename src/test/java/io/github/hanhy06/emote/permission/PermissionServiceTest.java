package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.PackConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void defaultAllowsOnlyConfiguredNamespaces() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue);
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of("emote.default", List.of("wave_pack"))
        ));

        assertTrue(service.canPlay(null, "wave_pack"));
        assertFalse(service.canPlay(null, "bow_pack"));
    }

    @Test
    void wildcardAllowsEveryNamespaceForPermission() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.pack.admin")
        );
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of(
                "emote.default", List.of(),
                "emote.pack.vip", List.of("bow_pack"),
                "emote.pack.admin", List.of("*")
            )
        ));

        assertTrue(service.canPlay(null, "bow_pack"));
        assertTrue(service.canPlay(null, "missing_pack"));
    }

    @Test
    void emptyPermissionsDenyEveryNamespace() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onPackConfigReload(new PackConfig(List.of(), Map.of()));

        assertFalse(service.canPlay(null, "wave_pack"));
    }

    @Test
    void defaultConfigAllowsEveryNamespace() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue
        );
        service.onPackConfigReload(PackConfig.createDefault());

        assertTrue(service.canPlay(null, "wave_pack"));
    }

    @Test
    void canPlayCombinesDefaultAndGrantedPermissionGroups() {
        PermissionService service = new PermissionService(
            (ignored, permission, defaultValue) -> defaultValue || permission.equals("emote.pack.vip")
        );
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of(
                "emote.default", List.of("wave_pack"),
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
        PermissionService service = new PermissionService(
            (ignored, permission, ignoredDefaultValue) -> permission.equals("emote.pack.admin")
        );
        service.onPackConfigReload(new PackConfig(
            List.of(),
            Map.of("emote.pack.admin", List.of("*"))
        ));

        assertTrue(service.canPlay(null, "missing_pack"));
    }

    @Test
    void explicitDefaultPermissionDenialRemovesDefaultAccess() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onPackConfigReload(PackConfig.createDefault());

        assertFalse(service.canPlay(null, "wave_pack"));
    }
}
