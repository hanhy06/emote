package io.github.hanhy06.emote.permission;

import io.github.hanhy06.emote.config.data.EmoteAccessConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void defaultAllowsOnlyConfiguredIds() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, defaultValue) -> defaultValue);
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
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
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
            List.of(),
            List.of(entry("emote.default", List.of()), entry("emote.admin", List.of("*")))
        ));

        assertTrue(service.canPlay(null, "demo:bow"));
        assertTrue(service.canPlay(null, "other:missing"));
    }

    @Test
    void emptyPermissionsDenyEveryId() {
        PermissionService service = new PermissionService((ignoredPlayer, ignoredPermission, ignoredDefaultValue) -> false);
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(List.of(), List.of()));

        assertFalse(service.canPlay(null, "demo:wave"));
    }

    @Test
    void findsFirstIdleEmoteForGrantedPermissionInConfigOrder() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.vip")
                || permission.equals("emote.default")
        );
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
            List.of(),
            List.of(
                new EmoteAccessConfig.PermissionEntry(
                    "emote.vip",
                    List.of("demo:vip"),
                    Optional.of(new EmoteAccessConfig.IdleEmote(300, "demo:vip"))
                ),
                new EmoteAccessConfig.PermissionEntry(
                    "emote.default",
                    List.of("*"),
                    Optional.of(new EmoteAccessConfig.IdleEmote(600, "demo:sit"))
                )
            )
        ));

        EmoteAccessConfig.IdleEmote idle = service.findIdleEmote(null).orElseThrow();

        assertEquals(300, idle.delaySeconds());
        assertEquals("demo:vip", idle.emote());
    }

    @Test
    void skipsGrantedPermissionWithoutIdleConfiguration() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, ignoredDefaultValue) -> permission.equals("emote.vip")
                || permission.equals("emote.default")
        );
        service.onEmoteAccessConfigReload(new EmoteAccessConfig(
            List.of(),
            List.of(
                entry("emote.vip", List.of("demo:vip")),
                new EmoteAccessConfig.PermissionEntry(
                    "emote.default",
                    List.of("*"),
                    Optional.of(new EmoteAccessConfig.IdleEmote(600, "demo:sit"))
                )
            )
        ));

        assertEquals("demo:sit", service.findIdleEmote(null).orElseThrow().emote());
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

    private EmoteAccessConfig.PermissionEntry entry(String permission, List<String> emotes) {
        return new EmoteAccessConfig.PermissionEntry(permission, emotes, Optional.empty());
    }
}
