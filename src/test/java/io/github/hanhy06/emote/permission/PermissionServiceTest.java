package io.github.hanhy06.emote.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void delegatesPermissionChecksToTheProvider() {
        PermissionService service = new PermissionService(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.vip") || defaultValue
        );

        assertTrue(service.has(null, "emote.vip", false));
        assertTrue(service.has(null, "emote.default", true));
        assertFalse(service.has(null, "emote.default", false));
    }
}
