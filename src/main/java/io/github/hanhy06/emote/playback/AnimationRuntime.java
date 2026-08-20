package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.animation.molang.MolangEngine;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimationTimeline;
import io.github.hanhy06.emote.content.PreparedEmote;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import static io.github.hanhy06.emote.content.PreparedAnimationTimeline.*;

final class AnimationRuntime {
    private final PreparedEmote emote;
    private final PreparedAnimationTimeline timeline;
    private final Map<String, PreparedEmote.PreparedTransform> transforms = new HashMap<>();
    private final Map<String, Boolean> visibility = new HashMap<>();

    private MolangEngine.Session session;

    AnimationRuntime(PreparedEmote emote) {
        this.emote = emote;
        this.timeline = emote.preparedTimeline();
    }

    Pose beginCycle(int tick, int loopCount) {
        this.session = MolangEngine.INSTANCE.createSession();
        setQueries(tick, loopCount, 0.0D);
        if (this.timeline.initialize() != null) {
            this.session.evaluate(this.timeline.initialize());
        }
        return evaluate(tick, loopCount, 0.0D, this.timeline.tick() != null);
    }

    Pose evaluate(int tick, int loopCount) {
        return evaluate(tick, loopCount, 0.05D, this.timeline.tick() != null);
    }

    PreparedEmote.PreparedTransform currentTransform(String nodeId) {
        return this.transforms.get(nodeId);
    }

    private Pose evaluate(int tick, int loopCount, double deltaTime, boolean runTick) {
        setQueries(tick, loopCount, deltaTime);
        if (runTick) {
            this.session.evaluate(this.timeline.tick());
        }

        this.transforms.clear();
        this.visibility.clear();
        Map<String, Matrix4f> resolved = new HashMap<>();
        EmoteAnimation animation = this.emote.animation();
        for (String nodeId : this.timeline.nodeOrder()) {
            Node node = animation.nodes().get(nodeId);
            CompiledNodeTracks tracks = this.timeline.tracks().get(nodeId);
            LocalTransform defaults = node.transform();
            double[] position = vector(tracks == null ? List.of() : tracks.position(), tick, defaults.position());
            double[] scale = vector(tracks == null ? List.of() : tracks.scale(), tick, defaults.scale());
            Quaternionf rotation = rotation(tracks == null ? List.of() : tracks.rotation(), tick, defaults.rotation());
            Matrix4f local = new Matrix4f()
                .translate((float) position[0], (float) position[1], (float) position[2])
                .rotate(rotation)
                .scale((float) scale[0], (float) scale[1], (float) scale[2]);
            Matrix4f matrix = node.parentId() == null
                ? local
                : new Matrix4f(resolved.get(node.parentId())).mul(local);
            resolved.put(nodeId, matrix);
            this.transforms.put(
                nodeId,
                PreparedEmote.PreparedTransform.create(matrix, node instanceof AnchorNode)
            );
            this.visibility.put(nodeId, visible(node, tracks, tick));
        }
        return new Pose(Map.copyOf(this.transforms), Map.copyOf(this.visibility));
    }

    private void setQueries(int tick, int loopCount, double deltaTime) {
        this.session.setQuery("anim_time", tick / 20.0D);
        this.session.setQuery("anim_time_ticks", tick);
        this.session.setQuery("anim_length", this.emote.durationTicks() / 20.0D);
        this.session.setQuery("delta_time", deltaTime);
        this.session.setQuery("loop_count", loopCount);
        this.session.setQuery("key_frame_lerp_time", 0.0D);
    }

    private double[] vector(List<CompiledVectorKeyframe> frames, int tick, Vec3 defaults) {
        if (frames.isEmpty()) {
            return new double[] {defaults.x(), defaults.y(), defaults.z()};
        }
        Segment segment = segment(frames, tick);
        this.session.setQuery("key_frame_lerp_time", segment.progress());
        double[] start = segment.current().post().evaluate(this.session);
        if (segment.next() == null || segment.current().interpolation() == Interpolation.STEP) {
            return start;
        }
        double[] end = segment.next().pre().evaluate(this.session);
        double progress = easing(segment.current().easing(), segment.progress());
        return new double[] {
            lerp(start[0], end[0], progress),
            lerp(start[1], end[1], progress),
            lerp(start[2], end[2], progress)
        };
    }

    private Quaternionf rotation(List<CompiledVectorKeyframe> frames, int tick, Vec3 defaults) {
        if (frames.isEmpty()) {
            return quaternion(new double[] {defaults.x(), defaults.y(), defaults.z()});
        }
        Segment segment = segment(frames, tick);
        this.session.setQuery("key_frame_lerp_time", segment.progress());
        Quaternionf start = quaternion(segment.current().post().evaluate(this.session));
        if (segment.next() == null || segment.current().interpolation() == Interpolation.STEP) {
            return start;
        }
        Quaternionf end = quaternion(segment.next().pre().evaluate(this.session));
        return start.slerp(end, (float) easing(segment.current().easing(), segment.progress()));
    }

    private boolean visible(Node node, CompiledNodeTracks tracks, int tick) {
        if (node instanceof AnchorNode) {
            return true;
        }
        if (tracks == null || tracks.visible().isEmpty()) {
            return node.visible();
        }
        CompiledVisibilityKeyframe current = tracks.visible().getFirst();
        for (CompiledVisibilityKeyframe frame : tracks.visible()) {
            if (frame.tick() > tick) {
                break;
            }
            current = frame;
        }
        this.session.setQuery("key_frame_lerp_time", 0.0D);
        return current.value().evaluate(this.session);
    }

    private Segment segment(List<CompiledVectorKeyframe> frames, int tick) {
        int currentIndex = 0;
        for (int index = 1; index < frames.size(); index++) {
            if (frames.get(index).tick() > tick) {
                break;
            }
            currentIndex = index;
        }
        CompiledVectorKeyframe current = frames.get(currentIndex);
        if (currentIndex + 1 >= frames.size()) {
            return new Segment(current, null, 0.0D);
        }
        CompiledVectorKeyframe next = frames.get(currentIndex + 1);
        double progress = (double) (tick - current.tick()) / (next.tick() - current.tick());
        return new Segment(current, next, Math.clamp(progress, 0.0D, 1.0D));
    }

    private Quaternionf quaternion(double[] degrees) {
        return new Quaternionf().rotationXYZ(
            (float) Math.toRadians(degrees[0]),
            (float) Math.toRadians(degrees[1]),
            (float) Math.toRadians(degrees[2])
        );
    }

    private double easing(Easing easing, double value) {
        return switch (easing) {
            case LINEAR -> value;
            case EASE_IN_SINE -> in(EasingKind.SINE, value);
            case EASE_OUT_SINE -> out(EasingKind.SINE, value);
            case EASE_IN_OUT_SINE -> inOut(EasingKind.SINE, value);
            case EASE_IN_QUAD -> in(EasingKind.QUAD, value);
            case EASE_OUT_QUAD -> out(EasingKind.QUAD, value);
            case EASE_IN_OUT_QUAD -> inOut(EasingKind.QUAD, value);
            case EASE_IN_CUBIC -> in(EasingKind.CUBIC, value);
            case EASE_OUT_CUBIC -> out(EasingKind.CUBIC, value);
            case EASE_IN_OUT_CUBIC -> inOut(EasingKind.CUBIC, value);
            case EASE_IN_QUART -> in(EasingKind.QUART, value);
            case EASE_OUT_QUART -> out(EasingKind.QUART, value);
            case EASE_IN_OUT_QUART -> inOut(EasingKind.QUART, value);
            case EASE_IN_QUINT -> in(EasingKind.QUINT, value);
            case EASE_OUT_QUINT -> out(EasingKind.QUINT, value);
            case EASE_IN_OUT_QUINT -> inOut(EasingKind.QUINT, value);
            case EASE_IN_EXPO -> in(EasingKind.EXPO, value);
            case EASE_OUT_EXPO -> out(EasingKind.EXPO, value);
            case EASE_IN_OUT_EXPO -> inOut(EasingKind.EXPO, value);
            case EASE_IN_CIRC -> in(EasingKind.CIRC, value);
            case EASE_OUT_CIRC -> out(EasingKind.CIRC, value);
            case EASE_IN_OUT_CIRC -> inOut(EasingKind.CIRC, value);
            case EASE_IN_BACK -> in(EasingKind.BACK, value);
            case EASE_OUT_BACK -> out(EasingKind.BACK, value);
            case EASE_IN_OUT_BACK -> inOut(EasingKind.BACK, value);
            case EASE_IN_ELASTIC -> in(EasingKind.ELASTIC, value);
            case EASE_OUT_ELASTIC -> out(EasingKind.ELASTIC, value);
            case EASE_IN_OUT_ELASTIC -> inOut(EasingKind.ELASTIC, value);
            case EASE_IN_BOUNCE -> in(EasingKind.BOUNCE, value);
            case EASE_OUT_BOUNCE -> out(EasingKind.BOUNCE, value);
            case EASE_IN_OUT_BOUNCE -> inOut(EasingKind.BOUNCE, value);
        };
    }

    private double in(EasingKind kind, double value) {
        return switch (kind) {
            case SINE -> 1.0D - Math.cos(Math.PI * value / 2.0D);
            case QUAD -> value * value;
            case CUBIC -> value * value * value;
            case QUART -> Math.pow(value, 4.0D);
            case QUINT -> Math.pow(value, 5.0D);
            case EXPO -> value == 0.0D ? 0.0D : Math.pow(2.0D, 10.0D * (value - 1.0D));
            case CIRC -> 1.0D - Math.sqrt(1.0D - value * value);
            case BACK -> value * value * ((1.70158D + 1.0D) * value - 1.70158D);
            case ELASTIC -> 1.0D - Math.pow(Math.cos(Math.PI * value / 2.0D), 3.0D) * Math.cos(Math.PI * value);
            case BOUNCE -> Math.min(
                Math.min(121.0D / 16.0D * value * value, 121.0D / 8.0D * Math.pow(value - 6.0D / 11.0D, 2.0D) + 0.5D),
                Math.min(
                    121.0D / 4.0D * Math.pow(value - 9.0D / 11.0D, 2.0D) + 0.75D,
                    121.0D / 2.0D * Math.pow(value - 10.5D / 11.0D, 2.0D) + 0.875D
                )
            );
        };
    }

    private double out(EasingKind kind, double value) {
        return 1.0D - in(kind, 1.0D - value);
    }

    private double inOut(EasingKind kind, double value) {
        return value < 0.5D ? in(kind, value * 2.0D) / 2.0D : 1.0D - in(kind, (1.0D - value) * 2.0D) / 2.0D;
    }

    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    record Pose(Map<String, PreparedEmote.PreparedTransform> transforms, Map<String, Boolean> visibility) {
    }

    private record Segment(CompiledVectorKeyframe current, CompiledVectorKeyframe next, double progress) {
    }

    private enum EasingKind {
        SINE,
        QUAD,
        CUBIC,
        QUART,
        QUINT,
        EXPO,
        CIRC,
        BACK,
        ELASTIC,
        BOUNCE
    }
}
