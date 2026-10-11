package io.github.hanhy06.emote.playback;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedAnimation;
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
