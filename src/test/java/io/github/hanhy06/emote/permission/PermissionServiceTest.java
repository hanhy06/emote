package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void defaultAllowsOnlyConfiguredIds() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue);
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
            List.of(),
            Map.of("emote.default", List.of("demo:wave"))
        ));

        assertTrue(service.canPlay(null, "demo:wave"));
        assertFalse(service.canPlay(null, "demo:bow"));
    }

    @Test
    void wildcardAllowsEveryIdForGrantedPermission() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.admin")
        );
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
            List.of(),
            Map.of("emote.default", List.of(), "emote.admin", List.of("*"))
        ));

        assertTrue(service.canPlay(null, "demo:bow"));
        assertTrue(service.canPlay(null, "other:missing"));
    }

    @Test
    void emptyPermissionsDenyEveryId() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(List.of(), Map.of()));

        assertFalse(service.canPlay(null, "demo:wave"));
    }

    @Test
    void defaultConfigAllowsEveryId() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue
        );
        service.onEmoteAccessConfigReload(EmoteAccessConfig.createDefault());

        assertTrue(service.canPlay(null, "demo:wave"));
    }

    @Test
    void explicitDefaultPermissionDenialRemovesDefaultAccess() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onEmoteAccessConfigReload(EmoteAccessConfig.createDefault());

        assertFalse(service.canPlay(null, "demo:wave"));
    }
}
