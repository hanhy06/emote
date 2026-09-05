package io.github.hanhy06.emote.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountCommandTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void onlyOwnersCanReachAnyAccountCommand() {
        var account = new AccountCommand(null).createCommand().build();
        for (PermissionLevel level : PermissionLevel.values()) {
            var source = Commands.createCompilationContext(LevelBasedPermissionSet.forLevel(level));
            boolean owner = level == PermissionLevel.OWNERS;
            assertEquals(owner, account.canUse(source));
            assertEquals(owner, account.getChild("login").canUse(source));
            assertEquals(owner, account.getChild("remove").canUse(source));
            assertEquals(owner, account.getChild("remove").getChild("account").canUse(source));
        }
    }

    @Test void removeNeverSuggestsAccountsAndNonOwnersCannotExecute() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(Commands.literal("emote").then(new AccountCommand(null).createCommand()));
        var owner = Commands.createCompilationContext(LevelBasedPermissionSet.forLevel(PermissionLevel.OWNERS));
        var admin = Commands.createCompilationContext(LevelBasedPermissionSet.forLevel(PermissionLevel.ADMINS));
        assertTrue(dispatcher.getCompletionSuggestions(dispatcher.parse("emote account remove ", owner)).get().isEmpty());
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> dispatcher.execute("emote account", admin));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> dispatcher.execute("emote account login", admin));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> dispatcher.execute("emote account remove Alpha", admin));
    }
}
