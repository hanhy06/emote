package io.github.hanhy06.emote.playback;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteLoadException;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
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
import team.unnamed.mocha.runtime.value.NumberValue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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
            MolangQuerySource querySource = (session, queryNames) -> {
                MolangQuerySource.EMPTY.apply(session);
                queries.entrySet().forEach(query -> session.setQuery(query.getKey(), query.getValue().getAsDouble()));
            };
            var target = new PoseTarget();
            var player = new EmotePlayback(animation, querySource);
            var initial = player.prepareStart(target);
            initial.calculate();
            initial.apply();
            initial.dispatchEvents();
            int elapsedTicks = 0;
            for (var frameElement : run.getAsJsonArray("frames")) {
                var frame = frameElement.getAsJsonObject();
                int tick = frame.get("elapsed_ticks").getAsInt();
                while (elapsedTicks < tick) {
                    var advance = player.prepareAdvance();
                    advance.calculate();
                    advance.apply();
                    advance.dispatchEvents();
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
        MolangQuerySource querySource = (session, queries) -> {
            MolangQuerySource.EMPTY.apply(session);
            session.setQueryFunction("scoreboard", arguments -> {
                String id = arguments.string(0);
                int count = calls.merge(id, 1, Integer::sum);
                if (id.equals("unused")) throw new IllegalStateException("Unused curve data was evaluated");
                return NumberValue.of(switch (id) {
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
        var evaluator = new AnimationEvaluator(animation, (session, queries) -> {
            MolangQuerySource.EMPTY.apply(session);
            session.setQueryFunction("scoreboard", arguments -> {
                String id = arguments.string(0);
                calls.add(id);
                return NumberValue.of(switch (id) {
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
            var evaluator = new AnimationEvaluator(animation, (session, queries) -> {
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
                    return NumberValue.of(value);
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
            var evaluator = new AnimationEvaluator(animation, (session, queries) -> {
                MolangQuerySource.EMPTY.apply(session);
                session.setQueryFunction("scoreboard", arguments -> {
                    String id = arguments.string(0);
                    calls.add(id);
                    return NumberValue.of(Double.parseDouble(id.substring(id.lastIndexOf(':') + 1)));
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

    @Test
    void calculatesRootAndTransitionWithoutMutatingPublishedComponents() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/bd-curves.json"))) { bytes = input.readAllBytes(); }
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("bd-curves.json"), bytes), registries);
        var evaluator = new AnimationEvaluator(animation, MolangQuerySource.EMPTY);
        evaluator.initialize(0, 0);
        var root = RootTransform.create(new net.minecraft.world.phys.Vec3(3, 80, -7), 37);
        evaluator.sampleFrame(4, 0, 1, 4, true, true);
        evaluator.calculatePose(evaluator.capturePose(), root.rotationMatrix(), null, 0);
        evaluator.finishFrame();
        var published = evaluator.transformation(0);
        var publishedMatrix = published.getMatrixCopy();
        var translation = new org.joml.Vector3f(published.translation());
        var rotation = new org.joml.Quaternionf(published.leftRotation());
        var scale = new org.joml.Vector3f(published.scale());
        var rightRotation = new org.joml.Quaternionf(published.rightRotation());
        var starts = new HashMap<String, Matrix4f>();
        for (int index = 0; index < evaluator.nodeCount(); index++) starts.put(evaluator.nodeId(index), new Matrix4f(evaluator.matrix(index)));

        evaluator.sampleFrame(10, 0, 1, 10, true, false);
        for (float progress : new float[]{0, 0.5f, 1, 0.25f}) {
            evaluator.calculatePose(evaluator.capturePose(), root.rotationMatrix(), starts, progress);
            for (int index = 0; index < evaluator.nodeCount(); index++) {
                var expectedPose = new Transformation(new Matrix4f(starts.get(evaluator.nodeId(index))))
                    .slerp(new Transformation(new Matrix4f(evaluator.matrix(index))), progress);
                assertEquals(expectedPose.getMatrix(), evaluator.poseMatrix(index));
                var expected = new Transformation(root.displayMatrix(expectedPose.getMatrix()));
                var actual = evaluator.transformation(index);
                assertEquals(expected.getMatrix(), actual.getMatrix());
                assertEquals(expected.translation(), actual.translation());
                assertEquals(expected.leftRotation(), actual.leftRotation());
                assertEquals(expected.scale(), actual.scale());
                assertEquals(expected.rightRotation(), actual.rightRotation());
            }
            assertEquals(publishedMatrix, published.getMatrix());
            assertEquals(translation, published.translation());
            assertEquals(rotation, published.leftRotation());
            assertEquals(scale, published.scale());
            assertEquals(rightRotation, published.rightRotation());
        }
        evaluator.sampleFrame(16, 0, 1, 16, true, false);
        evaluator.calculatePose(evaluator.capturePose(), root.rotationMatrix(), starts, 0.5f);
        var updatedEnd = new Transformation(new Matrix4f(evaluator.matrix(0)));
        var expectedUpdated = new Transformation(new Matrix4f(starts.get(evaluator.nodeId(0)))).slerp(updatedEnd, 0.5f);
        assertEquals(expectedUpdated.getMatrix(), evaluator.poseMatrix(0));
        var nextStarts = new HashMap<>(starts);
        nextStarts.put(evaluator.nodeId(0), new Matrix4f().translation(9, -2, 4));
        evaluator.calculatePose(evaluator.capturePose(), root.rotationMatrix(), nextStarts, 0.25f);
        assertEquals(new Transformation(new Matrix4f(nextStarts.get(evaluator.nodeId(0)))).slerp(updatedEnd, 0.25f).getMatrix(), evaluator.poseMatrix(0));
    }

    @Test
    void preparesInputsOnMainAndCalculatesWithoutApplyingOrDispatching() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/bd-curves.json"))) { bytes = input.readAllBytes(); }
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("bd-curves.json"), bytes), registries);
        var mainThread = Thread.currentThread();
        var workerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var release = new java.util.concurrent.CountDownLatch(1);
        var queryThreads = new ArrayList<Thread>();
        MolangQuerySource queries = (session, queryNames) -> {
            queryThreads.add(Thread.currentThread());
            MolangQuerySource.EMPTY.apply(session);
        };
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            var thread = Thread.ofPlatform().name("test pose worker").unstarted(runnable);
            workerThread.set(thread);
            return thread;
        })) {
            try {
                var target = new PoseTarget();
                var playback = new EmotePlayback(animation, queries, new java.util.Random(1));
                var initial = playback.prepareStart(target);
                assertTrue(target.matrices.isEmpty());
                target.root.translation(100, 0, 0);
                worker.submit(initial::calculate).get();
                assertTrue(target.matrices.isEmpty());
                initial.apply();
                initial.dispatchEvents();
                assertEquals(0, target.matrices.get("display_1").m30(), 1e-6);
                target.root.identity();
                var previous = Map.copyOf(target.matrices);
                var entered = new java.util.concurrent.CountDownLatch(1);
                worker.submit(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Pose gate timed out");
                    }
                    catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                });
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var advance = playback.prepareAdvance(4);
                var queryCount = queryThreads.size();
                var calculation = worker.submit(advance::calculate);
                assertEquals(previous, target.matrices);
                assertThrows(IllegalStateException.class, () -> playback.prepareTick(10));
                assertTrue(workerThread.get() != mainThread);
                release.countDown();
                calculation.get();
                assertEquals(queryCount, queryThreads.size());
                assertTrue(queryThreads.stream().allMatch(thread -> thread == mainThread));
                assertEquals(previous, target.matrices);
                advance.apply();
                advance.dispatchEvents();
                assertEquals(4, playback.currentTick());
                assertThrows(IllegalStateException.class, advance::calculate);

                var seek = playback.prepareTick(10);
                worker.submit(seek::calculate).get();
                seek.apply();
                seek.dispatchEvents();
                var referenceTarget = new PoseTarget();
                var reference = new EmotePlayback(animation);
                var referenceInitial = reference.prepareStart(referenceTarget);
                referenceInitial.calculate();
                referenceInitial.apply();
                referenceInitial.dispatchEvents();
                var referenceSeek = reference.prepareTick(10);
                referenceSeek.calculate();
                referenceSeek.apply();
                referenceSeek.dispatchEvents();
                assertEquals(referenceTarget.matrices, target.matrices);
                var discarded = playback.prepareAdvance();
                worker.submit(discarded::calculate).get();
                discarded.discard();
                assertEquals(referenceTarget.matrices, target.matrices);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void preparesSequenceBoundariesAndKeepsEachEventPoseUntilDispatch() throws Exception {
        JsonObject json;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/common-curves.json"))) {
            json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        json.add("nodes", JsonParser.parseString("{\"n\":{\"transform\":[{\"id\":\"position\",\"op\":\"translate\",\"value\":[0,0,0]}],\"attachments\":{\"text\":{\"type\":\"text_display\",\"text\":\"probe\"}}}}"));
        json.add("animation", JsonParser.parseString("""
            {"duration":"2t","playback":{"mode":"once","start_delay":"0t","loop_delay":"0t"},
             "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                         "driver":{"type":"expression","value":[{"molang":"q.anim_time_ticks"},0,0]}},
                       {"target":{"node":"n","attachment":"text"},"channel":"nbt",
                         "driver":{"type":"state","keys":[{"time":"0t","value":{"merge":{"molang":"return '{Glowing:1b}';"}}}]}}],
             "events":{"start":[{"source":{"type":"server"},"origin":{"type":"node","node":"n"},
                                   "action":{"type":"commands","commands":["say start"]}}],
                       "timeline":[{"time":"2t","source":{"type":"server"},"origin":{"type":"node","node":"n"},
                                     "action":{"type":"commands","commands":["say end"]}}],"loop":[],"stop":[]}}
            """));
        var first = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("first.json"), json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        json.addProperty("id", "test:second");
        json.getAsJsonObject("animation").getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonObject("driver")
            .add("value", JsonParser.parseString("[{\"molang\":\"10 + q.anim_time_ticks\"},0,0]"));
        var second = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("second.json"), json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var sequence = PreparedSequence.prepare(new EmoteSequence(net.minecraft.resources.Identifier.parse("test:sequence"), first.metadata(),
            new EmoteSequence.Settings(0, first.playerBehavior()), List.of(
                new EmoteSequence.AnimationStep(first.model().id(), 1),
                new EmoteSequence.AnimationStep(second.model().id(), 1, 2))), Map.of(first.id(), first, second.id(), second));
        var target = new PoseTarget();
        var playback = new EmotePlayback(sequence);
        var effects = new ArrayList<String>();
        playback.bindEvents(event -> effects.add("event:" + event.animationId() + ":" + event.animationTick() + ":" + event.pose().nodeMatrix("n").m30()));
        playback.bindLifecycleListener(new EmotePlayback.LifecycleListener() {
            public void onStart(PreparedAnimation animation) {
                effects.add("start:" + animation.id() + ":" + playback.position().animationTick() + ":" + playback.currentTransformation("n").getMatrix().m30());
            }
            public void onTick(int tick) {
                effects.add("tick:" + tick + ":" + playback.position().animationTick() + ":" + playback.currentTransformation("n").getMatrix().m30());
            }
            public void onClose(PlaybackStopReason reason) { effects.add("close:" + reason); }
        });
        var initial = playback.prepareStart(target);
        assertTrue(target.matrices.isEmpty());
        assertTrue(effects.isEmpty());
        initial.calculate();
        assertTrue(target.matrices.isEmpty());
        initial.apply();
        assertTrue(effects.isEmpty());
        initial.dispatchEvents();
        effects.clear();
        var advance = playback.prepareAdvance(3);
        assertEquals(io.github.hanhy06.emote.api.PlaybackTimeline.Phase.TRANSITION, playback.position().phase());
        assertTrue(effects.isEmpty());
        assertEquals(0, target.matrices.get("n").m30(), 1e-6);
        advance.calculate();
        assertTrue(effects.isEmpty());
        assertEquals(0, target.matrices.get("n").m30(), 1e-6);
        advance.apply();
        assertTrue(effects.isEmpty());
        assertEquals(6, target.matrices.get("n").m30(), 1e-6);
        advance.dispatchEvents();
        assertEquals(List.of("event:test:common_curves:2:2.0", "tick:2:2:2.0", "close:FINISHED"), effects);
        var next = playback.prepareAdvance(2);
        assertEquals(1, playback.position().animationTick());
        next.calculate();
        next.apply();
        assertEquals(11, target.matrices.get("n").m30(), 1e-6);
        assertTrue(target.nbt.get("n/text").getBooleanOr("Glowing", false));
        next.dispatchEvents();
        assertEquals(List.of("event:test:common_curves:2:2.0", "tick:2:2:2.0", "close:FINISHED",
            "event:test:second:0:10.0", "start:test:second:0:10.0", "tick:1:1:11.0"), effects);
        var stop = playback.prepareStop(PlaybackStopReason.MANUAL);
        stop.calculate();
        stop.apply();
        stop.dispatchEvents();
        assertEquals("close:MANUAL", effects.getLast());
    }

    @Test
    void preservesHugSampleSkinBindingsLocatorsAndPoseBoundaries() throws Exception {
        var prepared = new HashMap<String, PreparedAnimation>();
        var starts = new HashMap<String, Map<String, Matrix4f>>();
        var ends = new HashMap<String, Map<String, Matrix4f>>();
        for (String name : List.of("free_hug", "put_away_sign", "hug_player0", "hug_player1")) {
            var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("docs/sample/hug/hug." + name + ".json")), registries);
            prepared.put(name, animation);
            assertEquals(6, animation.skinBindings().stream().map(binding -> binding.region().part()).distinct().count());
            var playback = new EmotePlayback(animation);
            var target = new PoseTarget();
            var frame = playback.prepareStart(target);
            frame.calculate();
            frame.apply();
            frame.dispatchEvents();
            starts.put(name, new HashMap<>(target.matrices));
            for (int tick = 1; tick <= animation.model().clip().durationTicks(); tick++) {
                frame = playback.prepareAdvance();
                frame.calculate();
                frame.apply();
                frame.dispatchEvents();
            }
            assertEquals(EmotePlayback.AdvanceResult.FINISHED, frame.result(), name);
            ends.put(name, new HashMap<>(target.matrices));
        }
        var animations = new HashMap<String, PreparedAnimation>();
        prepared.values().forEach(animation -> animations.put(animation.id(), animation));
        var loaded = new io.github.hanhy06.emote.content.loader.SequenceJsonParser().parse(Path.of("docs/sample/hug/hug.hug.json"));
        var sequence = PreparedSequence.prepare(loaded.model(), animations);
        assertEquals(prepared.get("free_hug").skinBindings(), sequence.skinBindings());
        var callback = sequence.callbacks().getFirst();
        assertEquals(io.github.hanhy06.emote.ExampleCallbacks.HUG_CALLBACK_ID, callback.name());
        assertTrue(prepared.get("free_hug").callbacks().isEmpty());
        Matrix4f locator = starts.get("free_hug").get(callback.payload());
        assertNotNull(locator);
        float sceneScale = prepared.get("free_hug").defaultMatrix("scene").m00();
        assertEquals(2.0F / 16 * sceneScale, locator.m30(), 1e-6);
        assertEquals(-22.0F / 16 * sceneScale, locator.m32(), 1e-6);
        String player0 = "group:27735ace-8fd4-80f2-ce41-30ea848a4359";
        String player1 = "group:720a1a06-48d2-0063-3afe-c89591816345";
        for (var binding : prepared.get("hug_player0").skinBindings()) {
            String node = binding.nodeId();
            while (node != null && !node.equals(player0)) node = prepared.get("hug_player0").model().nodes().get(node).parentId();
            assertEquals(player0, node);
        }
        for (var binding : prepared.get("hug_player1").skinBindings()) {
            String node = binding.nodeId();
            while (node != null && !node.equals(player1)) node = prepared.get("hug_player1").model().nodes().get(node).parentId();
            assertEquals(player1, node);
        }
        for (String node : List.of(player0, "group:d2a3967c-f93f-b33a-1dcf-f1c88815d458", "group:969bcd71-7c25-781d-7073-e17294729eef")) {
            assertTrue(ends.get("free_hug").get(node).equals(starts.get("put_away_sign").get(node), 1e-5F), node);
            assertTrue(ends.get("put_away_sign").get(node).equals(starts.get("hug_player0").get(node), 1e-5F), node);
        }
        assertEquals(0.0F, starts.get("hug_player1").get(player1).m30(), 1e-6);
        assertEquals(0.0F, starts.get("hug_player1").get(player1).m32(), 1e-6);
    }

    @Test
    void compilesMolangControlFlowAndQueriesIntoPlaybackPoses() throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:molang_compiler","metadata":{"name":"Molang","description":""},
             "nodes":{"n":{"transform":[{"id":"position","op":"translate","value":[0,0,0]}],
                             "attachments":{"text":{"type":"text_display","text":"probe"}}}},
             "animation":{"duration":"4t",
                          "molang":{"initialize":"v.phase = q.anim_length > 0 ? 2 : q.life_time > 0 ? 3 : 4; q.is_on_ground ? { v.phase = v.phase + 5; } : { v.phase = v.phase + 7; }; loop(4, { v.phase = v.phase + 1; v.phase > 11 ? { return v.phase; }; }); v.after = 99;",
                                    "tick":"v.phase = q.anim_time_ticks < 2 ? v.phase + 1 : q.anim_time_ticks < 3 ? v.phase + 2 : v.phase + 3;"},
                          "values":{"offset":{"type":"expression","base":[3],"value":[3]}},
                          "tracks":[{"target":{"node":"n","operation":"position"},"channel":"value",
                                      "driver":{"type":"expression","value":[{"molang":"-math.abs(q.value('offset', 0)) + v.phase"},
                                          {"molang":"q.any('hand', 'hand', 'other') ? { return 2; } : { return 8; };"},{"molang":"v.after"}]}}]}}
            """).getAsJsonObject();
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("molang.json"),
            json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var playback = new EmotePlayback(animation);
        var target = new PoseTarget();
        float[] positions = {9, 10, 12, 15};
        for (int tick = 0; tick < positions.length; tick++) {
            var frame = tick == 0 ? playback.prepareStart(target) : playback.prepareAdvance();
            frame.calculate();
            frame.apply();
            frame.dispatchEvents();
            assertEquals(positions[tick], target.matrices.get("n").m30());
            assertEquals(2, target.matrices.get("n").m31());
            assertEquals(0, target.matrices.get("n").m32());
        }
        JsonObject component = json.getAsJsonObject("animation").getAsJsonArray("tracks").get(0).getAsJsonObject()
            .getAsJsonObject("driver").getAsJsonArray("value").get(0).getAsJsonObject();
        for (String expression : List.of("v.phase = q.anim_time > 0 ? 1 : 2;", "q.anim_time > 0 ? { v.phase = 1; } : 0;",
                "q.anim_time = 0;", "q.no_such_query", "q.value('offset')")) {
            component.addProperty("molang", expression);
            assertThrows(EmoteLoadException.class, () -> PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("molang.json"),
                json.toString().getBytes(StandardCharsets.UTF_8)), registries), expression);
        }
    }

    @Test
    void evaluatesMolangQuaternionAndMatrixTracksIntoHierarchicalPoses() throws Exception {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:dynamic_transforms","metadata":{"name":"Transforms","description":""},
             "nodes":{"rotation":{"transform":[{"id":"r","op":"rotate_quaternion","value":[0,0,0,1]}]},
                      "translation":{"parent":"rotation","transform":[{"id":"m","op":"matrix","value":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}]},
                      "slerp":{"transform":[{"id":"r","op":"rotate_quaternion","value":[0,0,0,1]}]},
                      "step":{"transform":[{"id":"m","op":"matrix","value":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}]}},
             "animation":{"duration":"3t","molang":{"initialize":"v.magnitude = 2;"},
                          "values":{"offset":{"base":[2],"type":"expression","value":[{"molang":"2 + q.anim_time_ticks"}]}},
                          "tracks":[
                 {"target":{"node":"rotation","operation":"r"},"channel":"value","driver":{"type":"expression",
                  "value":[0,{"molang":"v.magnitude * math.sin(q.anim_time_ticks * 45)"},0,{"molang":"v.magnitude * math.cos(q.anim_time_ticks * 45)"}]}},
                 {"target":{"node":"translation","operation":"m"},"channel":"value","driver":{"type":"expression",
                  "value":[1,0,0,{"molang":"q.value('offset', 0)"},0,1,0,0,0,0,1,0,0,0,0,1]}},
                 {"target":{"node":"slerp","operation":"r"},"channel":"value","driver":{"type":"curve","keys":[
                     {"time":"0t","value":[0,0,0,{"molang":"v.magnitude"}]},
                     {"time":"2t","pre":[0,{"molang":"v.magnitude"},0,0],"post":[0,0,0,1]}],"segments":[{"interpolation":"slerp"}]}},
                 {"target":{"node":"step","operation":"m"},"channel":"value","driver":{"type":"curve","keys":[
                     {"time":"0t","value":[1,0,0,{"molang":"7 + q.anim_time_ticks"},0,1,0,0,0,0,1,0,0,0,0,1]},
                     {"time":"2t","value":[1,0,0,{"molang":"9 + q.anim_time_ticks"},0,1,0,0,0,0,1,0,0,0,0,1]}],"segments":[{"interpolation":"step"}]}}]}}
            """).getAsJsonObject();
        var animation = PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("transforms.json"), json.toString().getBytes(StandardCharsets.UTF_8)), registries);
        var playback = new EmotePlayback(animation);
        var target = new PoseTarget();
        for (int tick = 0; tick < 3; tick++) {
            var frame = tick == 0 ? playback.prepareStart(target) : playback.prepareAdvance();
            frame.calculate();
            frame.apply();
            frame.dispatchEvents();
            Matrix4f rotation = new Matrix4f().rotateY((float) Math.toRadians(tick * 90));
            assertTrue(rotation.equals(target.matrices.get("rotation"), 1e-5F));
            assertTrue(new Matrix4f(rotation).translate(2 + tick, 0, 0).equals(target.matrices.get("translation"), 1e-5F));
            assertTrue(new Matrix4f().rotateY(tick == 1 ? (float) (Math.PI / 2) : 0).equals(target.matrices.get("slerp"), 1e-5F));
            assertEquals(tick < 2 ? 7 + tick : 9 + tick, target.matrices.get("step").m30());
        }
    }

    private static final class PoseTarget implements EmotePlayback.PoseTarget {
        final Map<String, Matrix4f> matrices = new HashMap<>();
        final Map<String, CompoundTag> nbt = new HashMap<>();
        final Matrix4f root = new Matrix4f();

        @Override public Matrix4fc rootMatrix() { return this.root; }
        @Override public Transformation createTransformation(String nodeId, Matrix4fc matrix) { return new Transformation(new Matrix4f(matrix)); }
        @Override public void applyTransform(String nodeId, Transformation transformation, int interpolationDurationTicks) { this.matrices.put(nodeId, transformation.getMatrixCopy()); }
        @Override public void setVisible(String nodeId, boolean visible) {}
        @Override public void setVisible(String nodeId, String attachmentId, boolean visible) {}
        @Override public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) { this.nbt.put(nodeId + "/" + attachmentId, nbt.copy()); }
        @Override public void resetAll() { this.matrices.clear(); }
    }
}
