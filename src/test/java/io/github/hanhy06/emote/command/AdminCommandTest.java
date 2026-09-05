package io.github.hanhy06.emote.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.playback.stress.StressTestPacketLoad;
import io.github.hanhy06.emote.server.ReloadResult;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Style;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static io.github.hanhy06.emote.playback.PlaybackEngine.*;
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
        assertNull(root.build().getChild("stop-all"));
    }

    @Test
    void stopPlayerArgumentAcceptsAllPlayersAndNames() throws Exception {
        var command = createCommand(true).createStopPlayerCommand().build();
        var argument = (ArgumentCommandNode<?, ?>) command.getChild("player");
        var type = (EntityArgument) argument.getType();

        assertEquals(Integer.MAX_VALUE, type.parse(new StringReader("@a")).getMaxResults());
        assertEquals(1, type.parse(new StringReader("Player")).getMaxResults());
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> type.parse(new StringReader("@e")));
    }

    @Test
    void listEntryShowsAdditionalMetadataWithoutStandaloneStatus() {
        var additional = new LinkedHashMap<String, JsonElement>();
        additional.put("author", new JsonPrimitive("@soji2318"));
        var credit = new JsonObject();
        credit.addProperty("author", "@soji2318");
        credit.addProperty("animation", "@animator");
        credit.addProperty("sound", "@composer");
        additional.put("credit", credit);
        var contributors = new JsonArray();
        contributors.add("@alice");
        contributors.add("@bob");
        additional.put("contributors", contributors);
        var entry = AdminCommand.createListEntry(
            "emote:dance",
            new EmoteMetadata("Dance", "A looping dance", additional),
            85,
            true
        );

        assertEquals(
            "\n• Dance  A looping dance\n  emote:dance · 4.3 seconds"
                + "\n  • author @soji2318"
                + "\n  • credit"
                + "\n      author @soji2318"
                + "\n      animation @animator"
                + "\n      sound @composer"
                + "\n  • contributors"
                + "\n      @alice"
                + "\n      @bob",
            entry.getString()
        );
    }

    @Test
    void listEntryMarksSequenceOnlyAnimations() {
        var entry = AdminCommand.createListEntry(
            "emote:dance_part",
            new EmoteMetadata("Dance part", "Used by a sequence"),
            20,
            false
        );

        assertEquals("\n• Dance part  Used by a sequence\n  emote:dance_part · 1.0 seconds · Sequence only", entry.getString());
    }

    @Test
    void reloadSummaryUsesNaturalLanguage() {
        var summary = AdminCommand.createReloadSummary(new ReloadResult(2, 4, 5, 3));

        assertEquals("Emotes reloaded\n 3 of 5 files loaded · 2 disabled · 4 permission rules", summary.getString());
    }

    @Test
    void stressTestRequiresTimeBeforeTheOptionalInstanceCountAndPacketFanout() {
        var command = createStressTestCommand(true).createCommand().build();
        assertNull(command.getCommand());

        var time = (ArgumentCommandNode<?, ?>) command.getChild("time");
        assertNotNull(time);
        assertInstanceOf(TimeArgument.class, time.getType());
        assertNotNull(time.getCommand());

        var count = (ArgumentCommandNode<?, ?>) time.getChild("count");
        assertNotNull(count);
        var countType = (IntegerArgumentType) count.getType();
        assertEquals(1, countType.getMinimum());
        assertEquals(500, countType.getMaximum());
        assertEquals(MAX_STRESS_TEST_INSTANCE_COUNT, countType.getMaximum());

        var packets = (ArgumentCommandNode<?, ?>) count.getChild("packets");
        assertNotNull(packets);
        var packetsType = (IntegerArgumentType) packets.getType();
        assertEquals(0, packetsType.getMinimum());
        assertEquals(500, packetsType.getMaximum());
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
            34_875_500_000L
        );
        var report = new PlaybackStressTestReport(
            100, 100, 500, 0, 600, 46.7D, 3_049.55D, 124.04D,
            10.0D, 20.0D, 25.0D, 30.0D,
            2.5D, 1.0D, 2.0D,
            37.3D, 2.0D, 4.0D,
            packetLoad
        );

        var summaryComponent = StressTestCommand.createPacketLoadSummary(report);
        String summary = summaryComponent.getString();
        assertTrue(summary.contains("Fanout: 20×"));
        assertTrue(summary.contains("Throughput: 46 packets/s / 0.05 MiB/s"));
        assertTrue(summary.contains("Tick: avg 2.000 ms / max 4.000 ms"));
        assertTrue(summary.contains("Encoding share: 93.5%"));
        assertTrue(summary.contains("Traffic/tick: avg 0.10 MiB / max 0.25 MiB"));
        assertFalse(summary.contains("median:"));
        assertFalse(summary.contains("p95:"));
        assertFalse(summary.toLowerCase().contains("client"));
        assertEquals(Style.EMPTY.withColor(ChatFormatting.GOLD), summaryComponent.getSiblings().get(6).getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.WHITE), summaryComponent.getSiblings().get(7).getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.GREEN), summaryComponent.getSiblings().get(8).getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.WHITE), summaryComponent.getSiblings().get(9).getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE), summaryComponent.getSiblings().get(10).getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.WHITE), summaryComponent.getSiblings().get(11).getStyle());

        assertEquals(
            "\n• Duration: 46.7 s\n  Setup 3.0 s + Emote 2.5 s + Network 37.3 s\n  + Server/idle 3.8 s + Cleanup 0.1 s",
            StressTestCommand.createStressDurationSummary(report).getString()
        );
        assertEquals(43.52641D, report.runtimeSeconds(), 0.00001D);
        assertEquals(13.78473D, report.observedTps(), 0.00001D);
    }

    @Test
    void stressTestStatisticColorsItsLabelAndKeepsItsValueWhite() {
        var statistic = StressTestCommand.createStressStatistic("\n  ", "avg", "%.2f ms", ChatFormatting.GREEN, 12.5D);

        assertEquals("\n  avg: 12.50 ms", statistic.getString());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.GREEN), statistic.getStyle());
        assertEquals(Style.EMPTY.withColor(ChatFormatting.WHITE), statistic.getSiblings().getFirst().getStyle());
    }

    private AdminCommand createCommand(boolean canManage) {
        return new AdminCommand(null, null, permissionService(canManage), null, null);
    }

    private StressTestCommand createStressTestCommand(boolean canManage) {
        return new StressTestCommand(null, null, permissionService(canManage));
    }

    private PermissionService permissionService(boolean canManage) {
        PermissionService permissionService = new PermissionService() {
            @Override
            public boolean canManage(CommandSourceStack source) {
                return canManage;
            }
        };
        return permissionService;
    }
}
