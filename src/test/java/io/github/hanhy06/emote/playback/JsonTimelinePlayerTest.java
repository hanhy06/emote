package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTimelinePlayerTest {
    @Test
    void sendsTransformAtInterpolationStartAndReachesTargetAtKeyframeTick() {
        FakeTarget target = new FakeTarget();
        EmoteAnimation animation = animation(
            10,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(
                keyframe(0, 0.0D, 0),
                keyframe(10, 10.0D, 4)
            )
        );
        JsonTimelinePlayer player = new JsonTimelinePlayer(animation, target);

        player.start();
        for (int tick = 1; tick <= 5; tick++) {
            player.advance();
        }
        assertEquals(List.of(new AppliedTransform(0.0D, 0)), target.transforms);

        player.advance();
        assertEquals(List.of(new AppliedTransform(0.0D, 0), new AppliedTransform(10.0D, 4)), target.transforms);
        player.advance();
        player.advance();
        assertEquals(5.0F, player.currentTransformation("node").getMatrix().m30(), 0.0001F);

        player.advance();
        player.advance();
        assertEquals(10.0F, player.currentTransformation("node").getMatrix().m30(), 0.0001F);
        assertEquals(JsonTimelinePlayer.AdvanceResult.FINISHED, player.advance());
    }

    @Test
    void exposesLoopBoundaryBeforeDelayAndRestart() {
        FakeTarget target = new FakeTarget();
        JsonTimelinePlayer player = new JsonTimelinePlayer(
            animation(
                2,
                EmoteAnimation.LoopMode.LOOP,
                2,
                List.of(keyframe(0, 0.0D, 0), keyframe(2, 2.0D, 0))
            ),
            target
        );

        player.start();
        assertEquals(JsonTimelinePlayer.AdvanceResult.CONTINUE, player.advance());
        assertEquals(JsonTimelinePlayer.AdvanceResult.LOOP_BOUNDARY, player.advance());
        assertEquals(2.0F, player.currentTransformation("node").getMatrix().m30(), 0.0001F);
        assertEquals(JsonTimelinePlayer.AdvanceResult.CONTINUE, player.continueAfterLoopEvent());
        assertEquals(JsonTimelinePlayer.AdvanceResult.CONTINUE, player.advance());
        assertEquals(JsonTimelinePlayer.AdvanceResult.RESTARTED, player.advance());
        assertEquals(0, player.currentTick());
        assertEquals(2, target.resetCount);
    }

    @Test
    void appliesVisibilityOnlyAtStateKeyframeTick() {
        FakeTarget target = new FakeTarget();
        EmoteAnimation animation = animation(
            3,
            EmoteAnimation.LoopMode.ONCE,
            0,
            List.of(
                new EmoteAnimation.Keyframe(2, Map.of(), Map.of("node", new EmoteAnimation.NodeState(false)))
            )
        );
        JsonTimelinePlayer player = new JsonTimelinePlayer(animation, target);

        player.start();
        player.advance();
        assertEquals(List.of(), target.visibility);
        player.advance();
        assertEquals(List.of(false), target.visibility);
    }

    private EmoteAnimation animation(
        int duration,
        EmoteAnimation.LoopMode loop,
        int delay,
        List<EmoteAnimation.Keyframe> keyframes
    ) {
        return new EmoteAnimation(
            Identifier.parse("test:timeline"),
            new EmoteAnimation.Metadata("Timeline", "Timeline", false),
            Map.of("node", new EmoteAnimation.AnchorNode(matrix(0.0D))),
            new EmoteAnimation.Timeline(duration, loop, delay, keyframes, EmoteAnimation.Events.empty())
        );
    }

    private EmoteAnimation.Keyframe keyframe(int tick, double x, int interpolation) {
        return new EmoteAnimation.Keyframe(
            tick,
            Map.of("node", new EmoteAnimation.NodeTransform(matrix(x), interpolation)),
            Map.of()
        );
    }

    private EmoteAnimation.Matrix matrix(double x) {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, x,
            0.0D, 1.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 1.0D, 0.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }

    private static final class FakeTarget implements JsonTimelinePlayer.TimelineTarget {
        private final List<AppliedTransform> transforms = new ArrayList<>();
        private final List<Boolean> visibility = new ArrayList<>();
        private int resetCount;

        @Override
        public Transformation createTransformation(EmoteAnimation.Matrix matrix) {
            return new Transformation(EmoteRootTransform.toJoml(matrix));
        }

        @Override
        public void applyTransform(String nodeId, EmoteAnimation.Matrix matrix, int interpolationDurationTicks) {
            this.transforms.add(new AppliedTransform(matrix.value(3), interpolationDurationTicks));
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.visibility.add(visible);
        }

        @Override
        public void resetAll() {
            this.resetCount++;
        }
    }

    private record AppliedTransform(double x, int duration) {
    }
}
