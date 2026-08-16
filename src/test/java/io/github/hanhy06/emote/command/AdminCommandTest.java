package io.github.hanhy06.emote.command;

import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.server.ReloadResult;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminCommandTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stopPlayerArgumentRequiresManagePermission() {
        var deniedCommand = createCommand(false).createStopPlayerCommand().build();
        var allowedCommand = createCommand(true).createStopPlayerCommand().build();

        assertNotNull(deniedCommand.getChild("player"));
        assertFalse(deniedCommand.getChild("player").getRequirement().test(null));
        assertTrue(allowedCommand.getChild("player").getRequirement().test(null));
    }

    @Test
    void stopPlayerArgumentCoexistsWithSelfStopCommand() {
        var root = Commands.<CommandSourceStack>literal("emote")
            .then(Commands.<CommandSourceStack>literal("stop").executes(ignoredContext -> 1));

        createCommand(true).attachTo(root);

        var stop = root.build().getChild("stop");
        assertNotNull(stop.getCommand());
        assertNotNull(stop.getChild("player"));
    }

    @Test
    void listEntrySeparatesFieldsAcrossReadableLines() {
        var entry = AdminCommand.createListEntry(
            "emote:dance",
            "Dance",
            "A looping dance",
            12,
            85,
            "emote.dance.json",
            "loop",
            true,
            false
        );

        assertEquals(
            """

            • emote:dance
              Dance — A looping dance
              Nodes: 12  Time: 85 ticks (4.3s)
              Source: emote.dance.json
              Loop: loop  Standalone: yes  Player: visible
            """.stripTrailing(),
            entry.getString()
        );
        assertEquals(TextColor.DARK_GRAY, entry.getStyle().getColor());
        assertEquals(TextColor.AQUA, entry.getSiblings().getFirst().getStyle().getColor());
    }

    @Test
    void reloadSummaryProducesOutput() {
        var summary = AdminCommand.createReloadSummary(new ReloadResult(2, 4, 5, 3));

        assertFalse(summary.getString().isBlank());
    }

    @Test
    void reloadSummaryHighlightsMatchingEmoteCountsInGreen() {
        var summary = AdminCommand.createReloadSummary(new ReloadResult(0, 1, 5, 5));

        long greenParts = summary.getSiblings().stream()
            .filter(component -> TextColor.GREEN.equals(component.getStyle().getColor()))
            .count();
        assertEquals(1, greenParts);
    }

    private AdminCommand createCommand(boolean canManage) {
        PermissionService permissionService = new PermissionService() {
            @Override
            public boolean canManage(CommandSourceStack source) {
                return canManage;
            }
        };
        return new AdminCommand(null, null, permissionService, null, null);
    }
}
