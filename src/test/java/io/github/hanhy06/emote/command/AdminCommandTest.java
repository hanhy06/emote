package io.github.hanhy06.emote.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.stress.PlaybackStressTestReport;
import io.github.hanhy06.emote.playback.stress.StressTestPacketLoad;
import io.github.hanhy06.emote.skin.SkinProcessingStats;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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
    void infoRequiresManagePermission() {
        assertFalse(createCommand(false).createInfoCommand().build().getRequirement().test(null));
        assertTrue(createCommand(true).createInfoCommand().build().getRequirement().test(null));
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

        String text = entry.getString();
        for (String value : new String[] {"Dance", "A looping dance", "emote:dance", "author", "@soji2318", "credit", "animation", "@animator", "sound", "@composer", "contributors", "@alice", "@bob"}) {
            assertTrue(text.contains(value), value);
        }
        assertFalse(text.contains("Sequence only"));
    }

    @Test
    void listEntryMarksSequenceOnlyAnimations() {
        var entry = AdminCommand.createListEntry(
            "emote:dance_part",
            new EmoteMetadata("Dance part", "Used by a sequence"),
            20,
            false
        );

        assertTrue(entry.getString().contains("emote:dance_part"));
        assertTrue(entry.getString().contains("Sequence only"));
    }

    @Test
    void infoSummaryShowsRuntimeCapacityAndSkinQueue() {
        var summary = AdminCommand.createInfoSummary(new AdminCommand.AdminInfoSnapshot(
            12,
            14,
            386,
            512,
            new SkinProcessingStats("Account", 2, 7, 1),
            43,
            3
        ));

        String text = summary.getString();
        for (String value : new String[] {"Sessions", "12", "Players", "14", "Displays", "386", "512", "Account", "Jobs", "2", "7", "Retries", "1", "Emotes", "43", "3"}) {
            assertTrue(text.contains(value), value);
        }
    }

    @Test
    void stressTestAcceptsSuffixedLoadBeforeTheOptionalPacketFanout() {
        var command = createStressTestCommand(true).createCommand().build();
        assertNull(command.getCommand());

        var time = (ArgumentCommandNode<?, ?>) command.getChild("time");
        assertNotNull(time);
        assertInstanceOf(TimeArgument.class, time.getType());
        assertNotNull(time.getCommand());

        var load = (ArgumentCommandNode<?, ?>) time.getChild("load");
        assertNotNull(load);
        assertInstanceOf(StringArgumentType.class, load.getType());

        var packets = (ArgumentCommandNode<?, ?>) load.getChild("packets");
        assertNotNull(packets);
        var packetsType = (IntegerArgumentType) packets.getType();
        assertEquals(0, packetsType.getMinimum());
        assertEquals(500, packetsType.getMaximum());
        assertEquals(MAX_STRESS_TEST_PACKET_FANOUT, packetsType.getMaximum());
        assertEquals(20, DEFAULT_STRESS_TEST_PACKET_FANOUT);
    }

    @Test
    void stressLoadDefaultsToInstancesAndAcceptsExplicitSuffixes() throws Exception {
        assertEquals(new StressTestCommand.StressLoad(100, StressTestCommand.LoadUnit.INSTANCES), StressTestCommand.parseLoad("100"));
        assertEquals(new StressTestCommand.StressLoad(100, StressTestCommand.LoadUnit.INSTANCES), StressTestCommand.parseLoad("100i"));
        assertEquals(new StressTestCommand.StressLoad(1_000, StressTestCommand.LoadUnit.DISPLAYS), StressTestCommand.parseLoad("1000d"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> StressTestCommand.parseLoad("0d"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> StressTestCommand.parseLoad("501i"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> StressTestCommand.parseLoad("100D"));
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
        for (String value : new String[] {"Fanout", "Throughput", "Tick", "Encoding share", "Traffic/tick"}) {
            assertTrue(summary.contains(value), value);
        }
        assertFalse(summary.toLowerCase().contains("client"));

        String duration = StressTestCommand.createStressDurationSummary(report).getString();
        for (String value : new String[] {"Duration", "Setup", "Emote", "Network", "Server/idle", "Cleanup"}) {
            assertTrue(duration.contains(value), value);
        }
        assertEquals(43.52641D, report.runtimeSeconds(), 0.00001D);
        assertEquals(13.78473D, report.observedTps(), 0.00001D);
    }

    private AdminCommand createCommand(boolean canManage) {
        return new AdminCommand(null, null, permissionService(canManage), null, null, null);
    }

    private StressTestCommand createStressTestCommand(boolean canManage) {
        return new StressTestCommand(null, null, permissionService(canManage));
    }

    private PermissionService permissionService(boolean canManage) {
        return new PermissionService(new PermissionService.PermissionBackend() {
            @Override
            public boolean has(CommandSourceStack source, String permission, PermissionLevel defaultLevel) {
                return canManage;
            }

            @Override
            public boolean has(ServerPlayer player, String permission, boolean defaultValue) {
                return defaultValue;
            }
        });
    }
}
