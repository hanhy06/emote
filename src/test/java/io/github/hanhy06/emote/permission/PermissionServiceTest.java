package io.github.hanhy06.emote.permission;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    @Test
    void delegatesEveryPermissionCheckToTheBackend() {
        AtomicInteger managementChecks = new AtomicInteger();
        AtomicInteger playerChecks = new AtomicInteger();
        PermissionService service = new PermissionService(new PermissionService.PermissionBackend() {
            @Override
            public boolean has(CommandSourceStack source, String permission, PermissionLevel defaultLevel) {
                managementChecks.incrementAndGet();
                assertEquals(PermissionService.MANAGE_PERMISSION, permission);
                assertEquals(PermissionLevel.GAMEMASTERS, defaultLevel);
                return true;
            }

            @Override
            public boolean has(ServerPlayer player, String permission, boolean defaultValue) {
                playerChecks.incrementAndGet();
                return permission.equals("emote.vip") || defaultValue;
            }
        });

        assertTrue(service.canManage(null));
        assertTrue(service.requireManage().test(null));
        assertTrue(service.has(null, "emote.vip", false));
        assertTrue(service.has(null, "emote.default", true));
        assertFalse(service.has(null, "emote.default", false));
        assertEquals(2, managementChecks.get());
        assertEquals(3, playerChecks.get());
    }
}
