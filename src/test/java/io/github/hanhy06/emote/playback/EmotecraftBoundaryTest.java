package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmotecraftBoundaryTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    private static PreparedAnimation animation(String name) throws Exception {
        try (var input = Objects.requireNonNull(EmotecraftBoundaryTest.class.getResourceAsStream("/" + name + ".json"))) {
            return PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of(name + ".json"), input.readAllBytes()), registries);
        }
    }

    private static Matrix4f pose(PreparedAnimation animation, String node, int tick, int loop) {
        var evaluator = new AnimationEvaluator(animation, MolangQuerySource.EMPTY);
        evaluator.initialize(0, 0);
        evaluator.prepareFrame(tick, loop, 1, tick + loop * 40L);
        evaluator.evaluateFrame(tick, loop, 1, tick + loop * 40L, false);
        for (int index = 0; index < evaluator.nodeCount(); index++) {
            if (evaluator.nodeId(index).equals(node)) return new Matrix4f(evaluator.matrix(index));
        }
        throw new AssertionError("Missing node " + node);
    }

    @Test
    void preservesExportedLoopSeamsAndTheirFormatAndEasingPolicies() throws Exception {
        var before = animation("emotecraft-loop");
        for (var sample : new double[][]{{5, 0, 0}, {30, 0, -0.625}, {39, 0, -0.34375}, {0, 1, -0.3125}, {5, 1, -0.15625}, {10, 1, 0}}) {
            assertEquals(sample[2], pose(before, "pal_body", (int) sample[0], (int) sample[1]).m30(), 1e-5);
        }
        var after = animation("emotecraft-loop-after");
        assertEquals(-5.0 / 6, pose(after, "pal_body", 30, 0).m30(), 1e-5);
        assertEquals(-25.0 / 48, pose(after, "pal_body", 0, 1).m30(), 1e-5);
        var returned = animation("emotecraft-loop-return");
        assertEquals(-0.5625, pose(returned, "pal_body", 30, 0).m30(), 1e-5);
        assertEquals(-0.1875, pose(returned, "pal_body", 5, 1).m30(), 1e-5);
        var gecko = animation("emotecraft-loop-gecko");
        assertEquals(-0.9375, pose(gecko, "pal_body", 30, 0).m30(), 1e-5);
        assertEquals(0, pose(gecko, "pal_body", 5, 1).m30(), 1e-5);
        var dynamic = animation("emotecraft-loop-dynamic");
        assertEquals(-35.0 / 48, pose(dynamic, "pal_body", 30, 0).m30(), 1e-5);
        assertEquals(-5.0 / 12, pose(dynamic, "pal_body", 0, 1).m30(), 1e-5);
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            assertEquals(expected.get(column, row), actual.get(column, row), 1e-5);
        }
    }

    @Test
    void preservesChannelSpecificBeginTransitionsAndEvaluatesEachEndpointOnce() throws Exception {
        var fade = animation("emotecraft-fade");
        float weight = (float) ((1 - Math.cos(Math.PI / 4)) / 2);
        var expected = new Matrix4f().scale(0.9375f).translate(-weight, 1.25f, 0)
            .rotateX((float) (-Math.PI / 2 * weight)).scale(1 + 2 * weight, 1, 1);
        assertMatrix(expected, pose(fade, "pal_body", 5, 0));
        assertEquals(-0.46875, pose(fade, "pal_body", 10, 0).m30(), 1e-5);
        assertEquals(-0.46875, pose(fade, "pal_body", 35, 0).m30(), 1e-5);
        var calls = new ArrayList<String>();
        var evaluator = new AnimationEvaluator(animation("emotecraft-fade-dynamic"), session -> {
            MolangQuerySource.EMPTY.apply(session);
            session.setQueryFunction("scoreboard", arguments -> {
                calls.add(arguments.string(0));
                return MolangEngine.QueryValue.number(0);
            });
        });
        evaluator.initialize(0, 0);
        evaluator.prepareFrame(5, 0, 1, 5);
        evaluator.evaluateFrame(5, 0, 1, 5, false);
        for (int index = 0; index < evaluator.nodeCount(); index++) if (evaluator.nodeId(index).equals("pal_body")) {
            assertEquals(-18.0 / 16 * weight * 0.9375, evaluator.matrix(index).m30(), 1e-5);
        }
        assertEquals(java.util.List.of("start", "end"), calls);
    }

    @Test
    void preservesPerChannelEndEasingAndItsLoopAndHoldExclusions() throws Exception {
        var fade = animation("emotecraft-fade-after");
        float weight = (float) ((1 + Math.cos(Math.PI / 5)) / 2);
        var body = new Matrix4f().scale(0.9375f).translate(-0.8f, 1.71f, 0)
            .rotateX((float) (-Math.PI / 2 * weight)).scale(3, 1, 1);
        assertMatrix(body, pose(fade, "pal_body", 32, 0));
        float bend = (float) (Math.PI / 3 * weight);
        var lower = new Matrix4f(body).translate(0, 0.75f, 0)
            .translate(0, (float) (-0.375 + 0.125 * (1 - Math.cos(bend))), (float) (0.125 * Math.sin(bend)))
            .rotateX(-bend);
        assertMatrix(lower, pose(fade, "pal_torso_lower", 32, 0));
        var steps = animation("emotecraft-fade-steps");
        assertEquals(-0.9375, pose(steps, "pal_body", 32, 0).m30(), 1e-5);
        assertEquals(-0.46875, pose(steps, "pal_body", 40, 0).m30(), 1e-5);
        assertEquals(-0.9375, pose(animation("emotecraft-fade-loop"), "pal_body", 32, 0).m30(), 1e-5);
        var hold = animation("emotecraft-loop-hold");
        assertEquals(-0.625, pose(hold, "pal_body", 30, 0).m30(), 1e-5);
        assertEquals(-0.3125, pose(hold, "pal_body", 40, 0).m30(), 1e-5);
    }
}
