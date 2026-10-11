package io.github.hanhy06.emote.playback;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplePoseTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    static Stream<Arguments> samples() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(SamplePoseTest.class.getResourceAsStream("/sample-poses.json")), StandardCharsets.UTF_8)) {
            var samples = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("samples");
            return samples.asList().stream().map(JsonElement::getAsJsonObject)
                .map(sample -> Arguments.of(sample.get("sample").getAsString(), sample));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void preservesReferencePoses(String samplePath, JsonObject sample) throws Exception {
        var loaded = new AnimationJsonParser().parse(Path.of("docs/sample").resolve(samplePath));
        var animation = PreparedAnimation.prepare(loaded, registries);
        for (var runElement : sample.getAsJsonArray("runs")) {
            var run = runElement.getAsJsonObject();
            var queries = run.getAsJsonObject("queries");
            MolangQuerySource querySource = session -> {
                MolangQuerySource.EMPTY.apply(session);
                queries.entrySet().forEach(query -> session.setQuery(query.getKey(), query.getValue().getAsDouble()));
            };
            var target = new PoseTarget();
            var player = new PlaybackPlayer(animation, querySource);
            player.start(target);
            int elapsedTicks = 0;
            for (var frameElement : run.getAsJsonArray("frames")) {
                var frame = frameElement.getAsJsonObject();
                int tick = frame.get("elapsed_ticks").getAsInt();
                while (elapsedTicks < tick) {
                    player.advance();
                    elapsedTicks++;
                }
                for (var node : frame.getAsJsonObject("matrices").entrySet()) {
                    String context = samplePath + " queries=" + queries + " elapsed=" + tick + " node=" + node.getKey();
                    Matrix4f actual = target.matrices.get(node.getKey());
                    assertNotNull(actual, context + " did not produce a pose");
                    var expected = node.getValue().getAsJsonArray();
                    for (int row = 0; row < 4; row++) {
                        for (int column = 0; column < 4; column++) {
                            double value = expected.get(row * 4 + column).getAsDouble();
                            assertEquals(value, actual.get(column, row), 1e-5 + 1e-5 * Math.abs(value),
                                context + " matrix[" + row + "][" + column + "]");
                        }
                    }
                }
            }
        }
    }

    @Test
    void evaluatesSharedCurvesDynamicArgumentsAndBlendInTrackOrder() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/common-curves.json"))) { bytes = input.readAllBytes(); }
        var loaded = new AnimationJsonParser().parse(Path.of("common-curves.json"), bytes);
        var animation = PreparedAnimation.prepare(loaded, registries);
        Map<String, Integer> calls = new HashMap<>();
        MolangQuerySource querySource = session -> {
            MolangQuerySource.EMPTY.apply(session);
            session.setQueryFunction("scoreboard", arguments -> {
                String id = arguments.string(0);
                int count = calls.merge(id, 1, Integer::sum);
                if (id.equals("unused")) throw new IllegalStateException("Unused curve data was evaluated");
                return MolangEngine.QueryValue.number(switch (id) {
                    case "weight" -> 0.5;
                    case "power" -> 2;
                    case "out_time" -> 1.0 / 3;
                    case "in_time" -> 2.0 / 3;
                    default -> count;
                });
            });
        };
        var evaluator = new AnimationEvaluator(animation, querySource);
        evaluator.initialize(0, 0);
        for (int tick : new int[]{0, 10, 15, 15, 30}) {
            calls.clear();
            evaluator.prepareFrame(tick, 0, 1, tick);
            evaluator.evaluateFrame(tick, 0, 1, tick, true);
            Map<String, Matrix4fc> matrices = new HashMap<>();
            for (int i = 0; i < evaluator.nodeCount(); i++) matrices.put(evaluator.nodeId(i), evaluator.matrix(i));
            assertEquals(1, calls.get("weight"));
            assertEquals(2, calls.get("random"));
            assertEquals(2, calls.get("order"));
            assertEquals(0.5, matrices.get("z").m31(), 1e-6);
            assertEquals(1.5, matrices.get("z").m32(), 1e-6);
            assertTrue(matrices.get("c").m30() > 2.1 && matrices.get("c").m30() < 2.9);
            assertEquals(1, matrices.get("s").m30(), 1e-6);
            assertTrue(!calls.containsKey("unused"));
            assertEquals(tick == 15 ? 1 : 0, calls.getOrDefault("power", 0));
            assertEquals(tick == 0 ? 0 : 3, calls.getOrDefault("out_time", 0));
            assertEquals(tick == 0 ? 0 : 3, calls.getOrDefault("in_time", 0));
            if (tick == 0) assertEquals(0, matrices.get("a").m30(), 1e-6);
            else assertEquals(matrices.get("z").m30(), matrices.get("a").m30(), 1e-6);
            if (tick == 15) {
                assertEquals(2.3125, matrices.get("a").m31(), 1e-6);
                assertEquals(0.875, matrices.get("a").m32(), 1e-6);
                assertEquals(2.8828125, matrices.get("b").m00(), 1e-6);
            }
        }
        for (String invalid : new String[]{"q.value('random', 0)", "q.value('missing', 0)", "q.value('bend', 1)"}) {
            JsonObject broken = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            broken.getAsJsonObject("animation").getAsJsonObject("values").getAsJsonObject("random").getAsJsonArray("value").get(0).getAsJsonObject().addProperty("molang", invalid);
            var invalidAnimation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("common-curves.json"), broken.toString().getBytes(StandardCharsets.UTF_8)), registries);
            var invalidEvaluator = new AnimationEvaluator(invalidAnimation, querySource);
            invalidEvaluator.initialize(0, 0);
            assertThrows(IllegalStateException.class, () -> invalidEvaluator.evaluateFrame(0, 0, 1, 0, true));
        }
        for (String invalid : new String[]{"exponent", "time"}) {
            JsonObject broken = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var tracks = broken.getAsJsonObject("animation").getAsJsonArray("tracks");
            JsonObject argument = invalid.equals("exponent")
                ? tracks.get(2).getAsJsonObject().getAsJsonObject("driver").getAsJsonArray("segments").get(0).getAsJsonObject().getAsJsonObject("easing").getAsJsonObject("exponent")
                : tracks.get(3).getAsJsonObject().getAsJsonObject("driver").getAsJsonArray("segments").get(0).getAsJsonObject().getAsJsonArray("handles").get(0).getAsJsonObject().getAsJsonObject("in").getAsJsonObject("time");
            argument.addProperty("molang", "-1");
            var invalidAnimation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("common-curves.json"), broken.toString().getBytes(StandardCharsets.UTF_8)), registries);
            var invalidEvaluator = new AnimationEvaluator(invalidAnimation, querySource);
            invalidEvaluator.initialize(0, 0);
            assertThrows(IllegalStateException.class, () -> invalidEvaluator.evaluateFrame(15, 0, 1, 15, true));
        }
        for (String kernel : new String[]{"blockbench_expo", "blockbench_steps"}) {
            JsonObject variant = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var bend = variant.getAsJsonObject("animation").getAsJsonObject("values").getAsJsonObject("bend");
            bend.getAsJsonArray("keys").get(0).getAsJsonObject().addProperty("time", "10t");
            bend.getAsJsonArray("keys").get(1).getAsJsonObject().addProperty("time", "30t");
            var easing = new JsonObject();
            easing.addProperty("kernel", kernel);
            easing.addProperty("direction", "in");
            if (kernel.equals("blockbench_steps")) easing.addProperty("count", 5);
            bend.getAsJsonArray("segments").get(0).getAsJsonObject().add("easing", easing);
            var variantAnimation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("common-curves.json"), variant.toString().getBytes(StandardCharsets.UTF_8)), registries);
            var variantEvaluator = new AnimationEvaluator(variantAnimation, querySource);
            variantEvaluator.initialize(0, 0);
            variantEvaluator.evaluateFrame(15, 0, 1, 15, true);
            double expectedBend = kernel.equals("blockbench_expo") ? 4 * Math.pow(2, -7.5) : 0.8;
            assertEquals((expectedBend + 0.25) * 0.5, variantEvaluator.matrix(0).m32(), 1e-6);
        }
    }

    private static final class PoseTarget implements PlaybackPlayer.TimelineTarget {
        final Map<String, Matrix4f> matrices = new HashMap<>();

        @Override public Transformation createTransformation(String nodeId, Matrix4fc matrix) { return new Transformation(new Matrix4f(matrix)); }
        @Override public void applyTransform(String nodeId, Matrix4fc matrix, int interpolationDurationTicks) { this.matrices.put(nodeId, new Matrix4f(matrix)); }
        @Override public void setVisible(String nodeId, boolean visible) {}
        @Override public void setVisible(String nodeId, String attachmentId, boolean visible) {}
        @Override public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) {}
        @Override public void resetAll() { this.matrices.clear(); }
    }
}
