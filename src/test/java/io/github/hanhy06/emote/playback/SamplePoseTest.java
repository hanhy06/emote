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
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void playsConvertedBbAndAjEasingsAgainstOriginalSourceValues() throws Exception {
        var reference = JsonParser.parseString(java.nio.file.Files.readString(Path.of("web/src/test/fixtures/blockbench-easings.json"))).getAsJsonObject();
        for (String format : new String[]{"bb", "aj"}) {
            byte[] bytes;
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/" + format + "-easings.json"))) { bytes = input.readAllBytes(); }
            var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of(format + "-easings.json"), bytes), registries);
            assertEquals(32, animation.model().clip().tracks().size());
            var evaluator = new AnimationEvaluator(animation, MolangQuerySource.EMPTY);
            evaluator.initialize(0, 0);
            double scale = format.equals("bb") ? 0.9375 : 1;
            for (int point = 1; point < reference.getAsJsonArray("progress").size() - 1; point++) {
                int tick = (int) Math.round(reference.getAsJsonArray("progress").get(point).getAsDouble() * 20);
                evaluator.prepareFrame(tick, 0, 1, tick);
                evaluator.evaluateFrame(tick, 0, 1, tick, true);
                Map<String, Matrix4fc> matrices = new HashMap<>();
                for (int i = 0; i < evaluator.nodeCount(); i++) matrices.put(evaluator.nodeId(i), evaluator.matrix(i));
                for (var entry : reference.getAsJsonArray("cases")) {
                    var easing = entry.getAsJsonObject();
                    String name = easing.get("name").getAsString();
                    double expected = easing.getAsJsonArray(format).get(point).getAsDouble();
                    assertEquals(expected, matrices.get(name).m30() / scale - 1, 1e-6, format + "." + name + "@" + tick);
                }
            }
        }
    }

    @Test
    void preservesBlockbenchKeySelectionAndRelativeControls() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/blockbench-curves.json"))) { bytes = input.readAllBytes(); }
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("blockbench-curves.json"), bytes), registries);
        List<String> calls = new ArrayList<>();
        var evaluator = new AnimationEvaluator(animation, session -> {
            MolangQuerySource.EMPTY.apply(session);
            session.setQueryFunction("scoreboard", arguments -> {
                String id = arguments.string(0);
                calls.add(id);
                return MolangEngine.QueryValue.number(switch (id) {
                    case "start", "start_y" -> 4;
                    case "end", "end_y" -> 8;
                    case "out" -> 2;
                    case "in" -> -1;
                    default -> throw new IllegalStateException("Unexpected query " + id);
                });
            });
        });
        evaluator.initialize(0, 0);
        int[] ticks = {0, 10, 20, 30, 31, 20, 0, 30, 10, 31};
        double[] expected = {2, 2, 6.375, 8, 10, 6.375, 2, 8, 2, 10};
        for (int point = 0; point < ticks.length; point++) {
            calls.clear();
            int tick = ticks[point];
            evaluator.prepareFrame(tick, 0, 1, tick);
            evaluator.evaluateFrame(tick, 0, 1, tick, true);
            assertEquals(expected[point], evaluator.matrix(0).m30(), 1e-6, "tick=" + tick);
            assertEquals(tick == 20 ? 6 : tick == 30 ? 8 : 0, evaluator.matrix(0).m31(), 1e-6, "Y tick=" + tick);
            assertEquals(tick == 20 ? List.of("start", "end", "out", "in", "start_y", "end_y") : tick == 30 ? List.of("end", "end_y") : List.of(), calls);
        }
    }

    @Test
    void playsConvertedBedrockCurvesWithSharedBlendAndSourceEvaluationOrder() throws Exception {
        for (String name : List.of("reference", "dynamic")) {
            byte[] bytes;
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/bedrock-" + name + ".json"))) { bytes = input.readAllBytes(); }
            var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("bedrock-" + name + ".json"), bytes), registries);
            List<String> calls = new ArrayList<>();
            var evaluator = new AnimationEvaluator(animation, session -> {
                MolangQuerySource.EMPTY.apply(session);
                session.setQueryFunction("scoreboard", arguments -> {
                    String id = arguments.string(0);
                    calls.add(id);
                    double value = switch (id) {
                        case "blend" -> 0.5;
                        case "angle" -> calls.stream().filter(id::equals).count() * 10;
                        case "scale" -> calls.stream().filter(id::equals).count() + 1;
                        default -> {
                            int axis = id.endsWith("_x") ? 1 : id.endsWith("_y") ? 2 : 3;
                            yield (id.startsWith("cat_start") ? 0 : id.startsWith("post") ? 64 : id.startsWith("end") ? 32 : 16) * axis;
                        }
                    };
                    return MolangEngine.QueryValue.number(value);
                });
            });
            evaluator.initialize(0, 0);
            int root = animation.nodeOrder().indexOf("bone:root"), prop = animation.nodeOrder().indexOf("bone:bedrock_custom_prop");
            int[] ticks = {0, 5, 10, 20, 30, 35, 40};
            double[] rootX = {0, 0, -0.5, -0.75, -2, -2, -2};
            double[] referencePropX = {0, -0.046875, -0.5, -0.96875, -2, -2.34375, -2.5};
            double[] dynamicPropX = {0, -0.1015625, -0.25, -0.5, -0.5, -0.5, -0.5};
            boolean dynamic = name.equals("dynamic");
            for (int point = 0; point < ticks.length; point++) {
                int tick = ticks[point];
                calls.clear();
                evaluator.prepareFrame(tick, 0, 1, tick);
                evaluator.evaluateFrame(tick, 0, 1, tick, true);
                float rx = dynamic ? -5 : -10, ry = dynamic ? -10 : -20, rz = dynamic ? 15 : 30;
                var expected = new Matrix4f().scaling(0.9375f).mul(new Matrix4f().translation((float) rootX[point], (float) (-2 * rootX[point]), (float) (-3 * rootX[point]))
                    .rotateZYX((float) Math.toRadians(rz), (float) Math.toRadians(ry), (float) Math.toRadians(rx)).scale(1.5f, dynamic ? 2 : 1.5f, dynamic ? 2.5f : 1.5f));
                for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
                    assertEquals(expected.get(column, row), evaluator.matrix(root).get(column, row), 1e-6, name + " root tick=" + tick);
                }
                double x = (dynamic ? dynamicPropX : referencePropX)[point] * 0.9375;
                assertEquals(x, evaluator.matrix(prop).m30(), 1e-6, name + " prop X tick=" + tick);
                assertEquals(-2 * x, evaluator.matrix(prop).m31(), 1e-6, name + " prop Y tick=" + tick);
                assertEquals(-3 * x, evaluator.matrix(prop).m32(), 1e-6, name + " prop Z tick=" + tick);
                List<String> expectedCalls = new ArrayList<>();
                if (dynamic) {
                    expectedCalls.add("blend");
                    if (tick >= 10 && tick < 30) {
                        expectedCalls.addAll(List.of("start_x", "start_y", "start_z"));
                        if (tick > 10) expectedCalls.addAll(List.of("end_x", "end_y", "end_z"));
                    } else if (tick >= 30) expectedCalls.addAll(List.of("post_x", "post_y", "post_z"));
                    expectedCalls.addAll(List.of("angle", "angle", "angle", "scale", "scale", "scale"));
                    if (tick < 20) expectedCalls.addAll(List.of("cat_start_x", "cat_start_y", "cat_start_z"));
                    if (tick > 0) expectedCalls.addAll(List.of("cat_end_x", "cat_end_y", "cat_end_z"));
                }
                assertEquals(expectedCalls, calls, name + " tick=" + tick);
            }
        }
    }

    @Test
    void playsConvertedEmotecraftAxesAndReusesBendAcrossJointAndInheritance() throws Exception {
        for (String name : List.of("reference", "dynamic")) {
            byte[] bytes;
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/emotecraft-" + name + ".json"))) { bytes = input.readAllBytes(); }
            var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("emotecraft-" + name + ".json"), bytes), registries);
            List<String> calls = new ArrayList<>();
            var evaluator = new AnimationEvaluator(animation, session -> {
                MolangQuerySource.EMPTY.apply(session);
                session.setQueryFunction("scoreboard", arguments -> {
                    String id = arguments.string(0);
                    calls.add(id);
                    return MolangEngine.QueryValue.number(Double.parseDouble(id.substring(id.lastIndexOf(':') + 1)));
                });
            });
            evaluator.initialize(0, 0);
            for (int tick : new int[]{0, 5, 10, 20, 40}) {
                calls.clear();
                evaluator.prepareFrame(tick, 0, 1, tick);
                evaluator.evaluateFrame(tick, 0, 1, tick, true);
                float slow = tick / 40f, fast = Math.min(1, tick / 20f), fastest = Math.min(1, tick / 10f);
                float bend = (float) Math.toRadians(60 * fast);
                var body = new Matrix4f().scaling(0.9375f).translate(-slow, 0.75f, 0)
                    .rotateZYX((float) Math.toRadians(20 * fastest), (float) Math.toRadians(-30 * slow), (float) Math.toRadians(-60 * fast))
                    .scale(1 + fast, 1 + 2 * fast, 1 + 3 * fast);
                var lower = new Matrix4f(body).translate(0, 0.75f, 0)
                    .translate(0, (float) (-0.375 + 0.125 * (1 - Math.cos(bend))), (float) (0.125 * Math.sin(bend))).rotateX(-bend);
                Map<String, Matrix4f> expected = new HashMap<>(Map.of("pal_body", body, "pal_torso_lower", lower));
                if (tick == 10) {
                    expected.put("pal_controls", new Matrix4f(body).translate(-10.25f / 16, -0.75f, 0));
                    var inherited = new Matrix4f(body).translate(0, 0.375f, 0).rotateX(-bend).translate(0, -0.375f, 0);
                    expected.put("pal_head", new Matrix4f(inherited).translate(-10.25f / 16, -0.75f, 0).translate(0, 1.5f, 0).rotateX((float) Math.toRadians(-45 * slow)));
                    float armBend = (float) Math.toRadians(13.125);
                    expected.put("pal_right_arm_lower", new Matrix4f(inherited).translate(5 / 16f, 10 / 16f, 0)
                        .translate(0, (float) (-0.375 + 0.125 * (1 - Math.cos(armBend))), (float) (0.125 * Math.sin(armBend))).rotateX(-armBend));
                }
                for (var entry : expected.entrySet()) {
                    var actual = evaluator.matrix(animation.nodeOrder().indexOf(entry.getKey()));
                    for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
                        assertEquals(entry.getValue().get(column, row), actual.get(column, row), 1e-6, name + " " + entry.getKey() + " tick=" + tick);
                    }
                }
                if (name.equals("dynamic")) {
                    assertEquals(2, calls.stream().filter(id -> id.startsWith("torso.bend")).count());
                    assertEquals(tick == 0 || tick >= 20 ? 2 : 6, calls.stream().filter(id -> id.startsWith("controls.position")).count());
                    assertEquals(List.of("body.rotation.0.0.start", "body.rotation.0.0.end", "body.rotation.1.0.start", "body.rotation.1.0.end", "body.rotation.2.0.start", "body.rotation.2.0.end"),
                        calls.subList(0, 6).stream().map(id -> id.substring(0, id.indexOf(':'))).toList());
                    if (tick == 10) assertEquals(List.of("controls.position.0.0.start", "controls.position.0.0.end", "controls.position.0.0.arg0", "controls.position.0.0.arg1", "controls.position.0.0.arg2", "controls.position.0.0.arg3"),
                        calls.stream().filter(id -> id.startsWith("controls.position")).map(id -> id.substring(0, id.indexOf(':'))).toList());
                }
            }
        }
    }

    @Test
    void playsConvertedBdCurvesAgainstMinecraftTransformationInterpolation() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/bd-curves.json"))) { bytes = input.readAllBytes(); }
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("bd-curves.json"), bytes), registries);
        var reference = JsonParser.parseString(java.nio.file.Files.readString(Path.of("web/src/test/fixtures/bd-curves.json"))).getAsJsonObject();
        Map<String, Transformation> original = new HashMap<>();
        for (var entry : reference.getAsJsonObject("matrices").entrySet()) {
            var matrix = new Matrix4f();
            var elements = entry.getValue().getAsJsonArray();
            for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) matrix.set(column, row, elements.get(row * 4 + column).getAsFloat());
            original.put(entry.getKey(), new Transformation(matrix));
        }
        Transformation initial = original.get("initial"), first = original.get("first"), second = original.get("second"), instant = original.get("instant");
        var interrupted = initial.slerp(first, 0.5f);
        var evaluator = new AnimationEvaluator(animation, MolangQuerySource.EMPTY);
        evaluator.initialize(0, 0);
        for (var point : reference.getAsJsonArray("ticks")) {
            int tick = point.getAsInt();
            evaluator.prepareFrame(tick, 0, 1, tick);
            evaluator.evaluateFrame(tick, 0, 1, tick, true);
            var expected = tick < 6 ? initial.slerp(first, Math.clamp((tick - 2) / 8f, 0, 1))
                : tick < 12 ? interrupted.slerp(second, Math.clamp((tick - 8) / 6f, 0, 1))
                : tick < 14 ? instant : instant.slerp(first, Math.clamp((tick - 12) / 8f, 0, 1));
            var step = original.get(tick < 4 ? "initial" : tick < 18 ? "instant" : "shear");
            for (var entry : Map.of("display_0", expected, "display_1", step).entrySet()) {
                var actual = evaluator.matrix(animation.nodeOrder().indexOf(entry.getKey()));
                var matrix = entry.getValue().getMatrix();
                for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
                    assertEquals(matrix.get(column, row), actual.get(column, row), 1e-5, entry.getKey() + " tick=" + tick);
                }
            }
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
