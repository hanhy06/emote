package io.github.hanhy06.emote.playback;

import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.coordinates.LocalCoordinates;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventCommandExecutorTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void resolvesExportedAjFunctionOriginsAtEachEventPose() throws Exception {
        byte[] bytes;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/aj-function-export.json"))) {
            bytes = stream.readAllBytes();
        }
        var animation = new AnimationJsonParser().parse(Path.of("aj-function-export.json"), bytes);
        var prepared = PreparedAnimation.prepare(animation, registries);
        var playback = new EmotePlayback(prepared);
        var events = new ArrayList<EmotePlayback.EventOccurrence>();
        playback.bindEvents(events::add);
        var target = new PoseTarget();
        var initial = playback.prepareStart(target);
        initial.calculate();
        initial.apply();
        initial.dispatchEvents();
        for (int tick = 1; tick <= 20; tick++) {
            var advance = playback.prepareAdvance(1);
            advance.calculate();
            advance.apply();
            advance.dispatchEvents();
        }
        assertEquals(5, events.size());

        double x = Math.toRadians(20), y = Math.toRadians(10), z = Math.toRadians(15);
        double localX = Math.cos(z) * Math.sin(y) * Math.cos(x) + Math.sin(z) * Math.sin(x);
        double localY = Math.sin(z) * Math.sin(y) * Math.cos(x) - Math.cos(z) * Math.sin(x);
        double localZ = Math.cos(y) * Math.cos(x);
        double pitch = Math.toDegrees(Math.asin(localY));
        double localYaw = Math.toDegrees(Math.atan2(localX, localZ));
        for (var occurrence : events) {
            var origin = occurrence.event().origin();
            assertTrue(origin.useOriginRotation());
            var point = EventCommandExecutor.resolveOrigin(origin, occurrence.pose());
            var rotation = EventCommandExecutor.resolveRotation(origin, occurrence.pose());
            if (origin.type() == EmoteAnimation.OriginType.ROOT) {
                assertEquals(EmoteAnimation.SourceType.NODE, occurrence.event().source().type());
                assertTrue(prepared.model().nodes().get(occurrence.event().source().node()).attachments().get("display") instanceof EmoteAnimation.SkinAttachment);
                assertEquals(target.root.position(), point);
                assertEquals(0, rotation.x, 1e-5);
                assertEquals(135, rotation.y, 1e-5);
                continue;
            }
            double parent = Math.toRadians(30 + 60 * occurrence.animationTick() / 20.0);
            double rx = 1 + Math.cos(parent) - Math.sin(parent), rz = 3 - Math.sin(parent) - Math.cos(parent);
            double facing = Math.toRadians(45);
            assertEquals(10 + 0.9375 * (Math.cos(facing) * rx + Math.sin(facing) * rz), point.x, 1e-5);
            assertEquals(20 + 0.9375 * 2.5, point.y, 1e-5);
            assertEquals(30 + 0.9375 * (-Math.sin(facing) * rx + Math.cos(facing) * rz), point.z, 1e-5);
            assertEquals(pitch, rotation.x, 1e-4);
            assertEquals(Mth.wrapDegrees((float) (135 - Math.toDegrees(parent) - localYaw)), rotation.y, 1e-4);

            var forward = new LocalCoordinates(0, 0, 1).apply(point, rotation).subtract(point);
            double worldYaw = facing + parent;
            assertEquals(-(Math.cos(worldYaw) * localX + Math.sin(worldYaw) * localZ), forward.x, 2e-4);
            assertEquals(-localY, forward.y, 2e-4);
            assertEquals(-(-Math.sin(worldYaw) * localX + Math.cos(worldYaw) * localZ), forward.z, 2e-4);

            var offsetOrigin = new EmoteAnimation.CommandOrigin(origin.type(), origin.node(), new Vec3(0.25, 0.5, -0.125), true);
            Vector3f offset = new Matrix4f().rotateY((float) facing).scale(0.9375F).translate(1, 2, 3).rotateY((float) parent).scale(2)
                .translate(0.5f, 0.25f, -0.5f).rotateZYX((float) z, (float) y, (float) x)
                .transformPosition(new Vector3f(0.25f, 0.5f, -0.125f));
            assertTrue(target.root.position().add(offset.x, offset.y, offset.z).distanceTo(EventCommandExecutor.resolveOrigin(offsetOrigin, occurrence.pose())) < 1e-5);
        }
        assertEquals(3, events.stream().filter(event -> event.event().origin().type() == EmoteAnimation.OriginType.NODE).count());
        assertEquals(0, events.getFirst().animationTick());
        assertEquals(20, events.getLast().animationTick());

        var legacy = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.getAsJsonObject("animation").getAsJsonObject("events").getAsJsonArray("timeline").get(0).getAsJsonObject().getAsJsonObject("origin").remove("rotation");
        var loadedLegacy = new AnimationJsonParser().parse(Path.of("legacy.json"), legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(loadedLegacy.model().clip().events().timeline().getFirst().event().origin().useOriginRotation());
    }

    private static final class PoseTarget implements EmotePlayback.PoseTarget {
        final RootTransform root = RootTransform.create(new Vec3(10, 20, 30), 45);

        @Override public Matrix4fc rootMatrix() { return this.root.rotationMatrix(); }
        @Override public RootTransform root() { return this.root; }
        @Override public float orientationYaw() { return 135; }
        @Override public Transformation createTransformation(String nodeId, Matrix4fc matrix) { return new Transformation(new Matrix4f(matrix)); }
        @Override public void applyTransform(String nodeId, Transformation transformation, int interpolationDurationTicks) {}
        @Override public void setVisible(String nodeId, boolean visible) {}
        @Override public void setVisible(String nodeId, String attachmentId, boolean visible) {}
        @Override public void applyNbt(String nodeId, String attachmentId, CompoundTag nbt, Set<String> remove) {}
        @Override public void resetAll() {}
    }
}
