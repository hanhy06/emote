package io.github.hanhy06.emote.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.playback.stress.StressTestPacketLoad;
import io.github.hanhy06.emote.server.ReloadResult;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hanhy06.emote.playback.PlaybackEngine.DEFAULT_STRESS_TEST_PACKET_FANOUT;
import static io.github.hanhy06.emote.playback.PlaybackEngine.MAX_STRESS_TEST_PACKET_FANOUT;
import static org.junit.jupiter.api.Assertions.*;

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
    void listEntryUsesPlayerFriendlySummary() {
        var entry = AdminCommand.createListEntry(
            "emote:dance",
            "Dance",
            "A looping dance",
            85,
            true,
            true
        );

        assertEquals("\n• Dance  A looping dance\n  emote:dance · 4.3 seconds · Hidden", entry.getString());
    }

    @Test
    void reloadSummaryUsesNaturalLanguage() {
        var summary = AdminCommand.createReloadSummary(new ReloadResult(2, 4, 5, 3));

        assertEquals("Emotes reloaded\n 3 of 5 files loaded · 2 disabled · 4 permission rules", summary.getString());
    }

    @Test
    void stressTestAcceptsAnOptionalPacketFanoutAfterTheInstanceCount() {
        var command = createCommand(true).createStressTestCommand().build();
        var count = command.getChild("count");
        assertNotNull(count);

        var packets = (ArgumentCommandNode<?, ?>) count.getChild("packets");
        assertNotNull(packets);
        var packetsType = (IntegerArgumentType) packets.getType();
        assertEquals(0, packetsType.getMinimum());
        assertEquals(MAX_STRESS_TEST_PACKET_FANOUT, packetsType.getMaximum());
        assertEquals(20, DEFAULT_STRESS_TEST_PACKET_FANOUT);
    }

    @Test
    void stressTestSummaryCallsTheMultiplierPacketFanout() {
        var packetLoad = new StressTestPacketLoad.PacketLoadResult(
            20,
            400,
            1_048_576,
            2_000,
            2_097_152,
            20,
            262_144,
            20_000_000,
            2_000_000
        );
        var report = new PlaybackStressTestReport(
            100, 100, 500, 0, 20, 2.0D, 10.0D, 5.0D,
            10.0D, 20.0D, 30.0D, 20.0D, 20.0D, 20.0D, 0.0D, 1.0D, 2.0D,
            packetLoad
        );

        String summary = AdminCommand.createPacketLoadSummary(report).getString();
        assertTrue(summary.contains("Fanout: 20×"));
        assertTrue(summary.contains("1,000 packets/s / 1.00 MiB/s"));
        assertFalse(summary.toLowerCase().contains("client"));
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
