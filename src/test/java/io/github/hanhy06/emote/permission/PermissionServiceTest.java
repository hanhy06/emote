package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.AccessConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceTest {
    @Test
    void disabledEmotesAreDeniedWithoutRemovingTheirPermissionRule() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue);
        service.onAccessConfigReload(new AccessConfig(
            List.of("demo:wave"),
            List.of(entry("emote.default", List.of("demo:wave")))
        ));

        assertFalse(service.canPlay(null, "demo:wave"));
    }

    @Test
    void bypassIgnoresDisabledAndPermissionRules() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals(PermissionService.BYPASS_PERMISSION)
        );
        service.onAccessConfigReload(new AccessConfig(List.of("demo:wave"), List.of()));

        assertTrue(service.canBypass(null));
        assertTrue(service.canPlay(null, "demo:wave"));
    }

    @Test
    void defaultAllowsOnlyConfiguredIds() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue);
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(entry("emote.default", List.of("demo:wave")))
        ));

        assertTrue(service.canPlay(null, "demo:wave"));
        assertFalse(service.canPlay(null, "demo:bow"));
    }

    @Test
    void wildcardAllowsEveryIdForGrantedPermission() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.admin")
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(entry("emote.default", List.of()), entry("emote.admin", List.of("*")))
        ));

        assertTrue(service.canPlay(null, "demo:bow"));
        assertTrue(service.canPlay(null, "other:missing"));
    }

    @Test
    void emptyPermissionsDenyEveryId() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onAccessConfigReload(new AccessConfig(List.of(), List.of()));

        assertFalse(service.canPlay(null, "demo:wave"));
    }

    @Test
    void findsFirstIdleEmoteForGrantedPermissionInConfigOrder() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.vip")
                || permission.equals("emote.default")
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(
                new AccessConfig.PermissionEntry(
                    "emote.vip",
                    List.of("demo:vip"),
                    Optional.of(new AccessConfig.IdleSettings(300, List.of("demo:vip", "demo:dance")))
                ),
                new AccessConfig.PermissionEntry(
                    "emote.default",
                    List.of("*"),
                    Optional.of(new AccessConfig.IdleSettings(600, List.of("demo:sit")))
                )
            )
        ));

        AccessConfig.IdleSettings idle = service.findIdleSettings(null).orElseThrow();

        assertEquals(300, idle.delayTicks());
        assertEquals(List.of("demo:vip", "demo:dance"), idle.emote());
    }

    @Test
    void skipsGrantedPermissionWithoutIdleConfiguration() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.vip")
                || permission.equals("emote.default")
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(
                entry("emote.vip", List.of("demo:vip")),
                new AccessConfig.PermissionEntry(
                    "emote.default",
                    List.of("*"),
                    Optional.of(new AccessConfig.IdleSettings(600, List.of("demo:sit")))
                )
            )
        ));

        assertEquals(List.of("demo:sit"), service.findIdleSettings(null).orElseThrow().emote());
    }

    @Test
    void defaultConfigAllowsEveryId() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue
        );
        service.onAccessConfigReload(AccessConfig.createDefault());

        assertTrue(service.canPlay(null, "demo:wave"));
    }

    @Test
    void explicitDefaultPermissionDenialRemovesDefaultAccess() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onAccessConfigReload(AccessConfig.createDefault());

        assertFalse(service.canPlay(null, "demo:wave"));
    }

    private AccessConfig.PermissionEntry entry(String permission, List<String> emotes) {
        return new AccessConfig.PermissionEntry(permission, emotes, Optional.empty());
    }
}
