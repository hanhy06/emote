package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
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
}
