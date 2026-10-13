package io.github.hanhy06.emote.playback;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.molang.MolangEngine;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.*;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import io.github.hanhy06.emote.content.PreparedAnimation;

final class AnimationEvaluator {
    private final PreparedAnimation animation;
    private final MolangQuerySource querySource;
    private final List<String> nodeIds;
    private final Matrix4f[] matrices;
    private final Matrix4fc[] poseMatrices;
    private final Transformation[] transformations;
    private final Transformation[] transitionStarts, transitionEnds;
    private Map<String, Matrix4f> transitionInput;
    private long sampleGeneration, transitionGeneration;
    private final Quaternionf[] orientations;
    private final boolean[] visibility;
    private final Track[][] tracks;
    private final Track[] nodeVisibilityTracks;
    private final List<Map<String, Track>> attachmentVisibilityTracks = new ArrayList<>();
    private final List<Map<String, Boolean>> attachmentVisibility = new ArrayList<>();
    private final Map<Track, Boolean> defaultVisibility = new IdentityHashMap<>();
    private final Map<Track, double[]> sampledValues = new IdentityHashMap<>();
    private final Map<Track, Boolean> sampledVisibility = new IdentityHashMap<>();
    private final Map<Track, double[]> blendedValues = new IdentityHashMap<>();
    private final Map<Driver, int[]> keyCursors = new IdentityHashMap<>();
    private final Matrix4f local = new Matrix4f(), operationMatrix = new Matrix4f();
    private final Matrix4f displayMatrix = new Matrix4f();
    private final Matrix4f inherited = new Matrix4f(), residual = new Matrix4f(), parentLinear = new Matrix4f();
    private final Quaternionf operationRotation = new Quaternionf();
    private final Quaterniond quaternion = new Quaterniond(), slerpStart = new Quaterniond(), slerpEnd = new Quaterniond();
    private final Map<String, Map<String, Track>> nbtTracks = new HashMap<>();
    private final Map<String, Map<String, CompoundTag>> nbt = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> nbtRemoved = new HashMap<>();
    private final Map<String, CompoundTag> nbtCaptures = new HashMap<>();
    private int nbtCycle;
    private MolangEngine.Session session;
    private final Map<String, double[]> sharedValues = new HashMap<>();
    private final Set<String> evaluatingValues = new HashSet<>();
    private int sampleTick, sampleLoop;
    private double sampleProgress;

    AnimationEvaluator(PreparedAnimation animation, MolangQuerySource querySource) {
        this.animation = animation;
        this.querySource = querySource;
        this.nodeIds = animation.nodeOrder();
        int count = this.nodeIds.size();
        this.matrices = new Matrix4f[count];
        this.poseMatrices = new Matrix4fc[count];
        this.transformations = new Transformation[count];
        this.transitionStarts = new Transformation[count];
        this.transitionEnds = new Transformation[count];
        this.orientations = new Quaternionf[count];
        this.visibility = new boolean[count];
        this.tracks = new Track[count][];
        this.nodeVisibilityTracks = new Track[count];
        Map<String, Map<String, Track>> valueTracks = new HashMap<>();
        Map<String, Track> visibilityTracks = new HashMap<>();
        Map<String, Map<String, Track>> attachmentTracks = new HashMap<>();
        for (var track : animation.model().clip().tracks().values()) {
            String node = track.target().node();
            if (track.channel() == Channel.VALUE) {
                valueTracks.computeIfAbsent(node, ignored -> new HashMap<>()).put(track.target().operation(), track);
                if (track.blend() != null) this.blendedValues.put(track, new double[track.blend().base().size()]);
            } else if (track.channel() == Channel.NBT) {
                this.nbtTracks.computeIfAbsent(node, ignored -> new TreeMap<>()).put(track.target().attachment(), track);
            } else if (track.target().attachment() == null) {
                visibilityTracks.put(node, track);
                this.defaultVisibility.put(track, animation.nodes().get(node).visible());
            } else {
                attachmentTracks.computeIfAbsent(node, ignored -> new HashMap<>()).put(track.target().attachment(), track);
                this.defaultVisibility.put(track, animation.nodes().get(node).attachments().get(track.target().attachment()).visible());
            }
        }
        for (int index = 0; index < count; index++) {
            String id = this.nodeIds.get(index);
            Node node = animation.node(index);
            this.matrices[index] = new Matrix4f();
            this.orientations[index] = new Quaternionf();
            this.tracks[index] = new Track[node.transform().size()];
            for (int operation = 0; operation < this.tracks[index].length; operation++) {
                this.tracks[index][operation] = valueTracks.getOrDefault(id, Map.of()).get(node.transform().get(operation).id());
            }
            this.nodeVisibilityTracks[index] = visibilityTracks.get(id);
            this.attachmentVisibilityTracks.add(attachmentTracks.getOrDefault(id, Map.of()));
            this.attachmentVisibility.add(new LinkedHashMap<>());
        }
    }

    void initialize(int loopCount, long lifetimeTicks) {
        if (this.session != null) return;
        this.session = MolangEngine.INSTANCE.createSession();
        this.session.setQueryFunction("value", arguments -> {
            if (!arguments.isString(0) || !arguments.isNumber(1)) throw new IllegalStateException("q.value requires a value id and component index");
            return MolangEngine.QueryValue.number(sharedValue(arguments.string(0), arguments.number(1)));
        });
        this.nbtCycle = loopCount;
        prepareFrame(0, loopCount, 0, lifetimeTicks);
        if (this.animation.model().molang().initialize() != null) {
            this.session.evaluate(this.animation.expression("$.animation.programs.initialize"));
        }
    }

    void prepareFrame(int tick, int loopCount, int deltaTicks, long lifetimeTicks) {
        setFrameQueries(tick, loopCount, deltaTicks, lifetimeTicks);
        this.querySource.apply(this.session, this.animation.queryNames());
    }

    int nextTick(int previousTick, int loopCount, int deltaTicks, long lifetimeTicks) {
        prepareFrame(previousTick, loopCount, deltaTicks, lifetimeTicks);
        if (this.animation.model().clip().clock() == null) return Math.addExact(previousTick, deltaTicks);
        double tick = this.session.evaluate(this.animation.expression("$.animation.clock.expression")) * 20;
        if (!Double.isFinite(tick) || tick > Integer.MAX_VALUE) throw new IllegalStateException("$.animation.clock.expression produced an invalid tick count");
        return (int) Math.round(Math.max(0, tick));
    }

    int delay(ScalarValue delay, int tick, int loopCount, int deltaTicks, long lifetimeTicks) {
        setFrameQueries(tick, loopCount, deltaTicks, lifetimeTicks);
        double value = switch (delay) {
            case ConstantValue constant -> constant.value();
            case MolangValue molang -> this.session.evaluate(this.animation.expression(molang.path())) * 20;
        };
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE) {
            String path = delay instanceof MolangValue molang ? molang.path() : "$.animation.playback";
            throw new IllegalStateException(path + " must evaluate to a finite non-negative delay");
        }
        return (int) Math.round(value);
    }

    int nodeCount() { return this.nodeIds.size(); }
    String nodeId(int index) { return this.nodeIds.get(index); }
    Matrix4fc matrix(int index) { return this.matrices[index]; }
    Matrix4fc poseMatrix(int index) { return this.poseMatrices[index]; }
    Transformation transformation(int index) { return this.transformations[index]; }
    boolean visible(int index) { return this.visibility[index]; }
    Map<String, Boolean> attachmentVisibility(int index) { return this.attachmentVisibility.get(index); }
    Map<String, CompoundTag> nbt(int index) { return this.nbt.getOrDefault(nodeId(index), Map.of()); }
    Set<String> nbtRemoved(int index, String attachmentId) { return this.nbtRemoved.get(nodeId(index)).get(attachmentId); }

    private void setFrameQueries(int tick, int loopCount, int deltaTicks, long lifetimeTicks) {
        if (tick != this.sampleTick || loopCount != this.sampleLoop) this.sharedValues.clear();
        this.sampleTick = tick;
        this.sampleLoop = loopCount;
        this.sampleProgress = 0;
        this.session.setQuery("anim_time", tick / 20.0);
        this.session.setQuery("anim_time_ticks", tick);
        this.session.setQuery("anim_length", this.animation.model().clip().durationTicks() / 20.0);
        this.session.setQuery("delta_time", deltaTicks / 20.0);
        this.session.setQuery("loop_count", loopCount);
        this.session.setQuery("key_frame_lerp_time", 0);
        this.session.setQuery("life_time", lifetimeTicks / 20.0);
    }

    void evaluateFrame(int tick, int loopCount, int deltaTicks, long lifetimeTicks, boolean update) {
        sampleFrame(tick, loopCount, deltaTicks, lifetimeTicks, update, true);
        finishFrame();
        calculatePose(capturePose(), new Matrix4f(), null, 0);
    }

    void sampleFrame(int tick, int loopCount, int deltaTicks, long lifetimeTicks, boolean update, boolean captureNbt) {
        this.sampleGeneration++;
        tick = Math.min(tick, this.animation.model().clip().durationTicks());
        this.sharedValues.clear();
        setFrameQueries(tick, loopCount, deltaTicks, lifetimeTicks);
        if (update && this.animation.model().molang().update() != null) {
            this.session.evaluate(this.animation.expression("$.animation.programs.update"));
        }
        if (this.nbtTracks.isEmpty() || this.animation.playbackMode() == PlaybackMode.SERVER_SYNC) this.nbtCycle = loopCount;
        Map<String, double[]> frameValues = captureNbt && this.nbtCycle < loopCount ? new HashMap<>(this.sharedValues) : null;
        while (captureNbt && this.nbtCycle < loopCount) {
            setFrameQueries(this.animation.model().clip().durationTicks(), this.nbtCycle, deltaTicks, lifetimeTicks);
            evaluateNbt(this.animation.model().clip().durationTicks());
            this.nbtCaptures.clear();
            this.nbtCycle++;
            setFrameQueries(this.animation.model().settings().playback().loopStartTick(), this.nbtCycle, deltaTicks, lifetimeTicks);
            evaluateNbt(this.animation.model().settings().playback().loopStartTick());
        }
        setFrameQueries(tick, loopCount, deltaTicks, lifetimeTicks);
        if (frameValues != null) {
            this.sharedValues.clear();
            this.sharedValues.putAll(frameValues);
        }
        this.sampledValues.clear();
        this.sampledVisibility.clear();
        for (Track track : this.animation.model().clip().tracks().values()) {
            if (track.channel() == Channel.VALUE) {
                Operation operation = this.animation.operation(track);
                double weight = track.blend() == null ? 1 : scalar(track.blend().weight(), 0);
                double[] value = sample(track.driver(), tick, operation.value());
                if (track.blend() != null) {
                    double[] blended = this.blendedValues.get(track);
                    for (int i = 0; i < value.length; i++) {
                        double base = track.blend().base().get(i);
                        blended[i] = base + (value[i] - base) * weight;
                    }
                    value = blended;
                }
                this.sampledValues.put(track, value);
            } else if (track.channel() == Channel.VISIBLE) {
                this.sampledVisibility.put(track, sampleVisibility(track, tick, this.defaultVisibility.get(track)));
            }
        }
    }

    Pose capturePose() {
        Pose pose = new Pose(this.sampleGeneration, this.nodeIds.size());
        this.sampledValues.forEach((track, values) -> pose.values.put(track, values.clone()));
        pose.sampledVisibility.putAll(this.sampledVisibility);
        this.nbt.forEach((node, attachments) -> {
            Map<String, CompoundTag> states = new HashMap<>();
            attachments.forEach((attachment, state) -> states.put(attachment, state.copy()));
            pose.nbt.put(node, states);
        });
        this.nbtRemoved.forEach((node, attachments) -> {
            Map<String, Set<String>> removed = new HashMap<>();
            attachments.forEach((attachment, fields) -> removed.put(attachment, Set.copyOf(fields)));
            pose.nbtRemoved.put(node, removed);
        });
        return pose;
    }

    static final class Pose {
        private final long generation;
        private final Map<Track, double[]> values = new IdentityHashMap<>();
        private final Map<Track, Boolean> sampledVisibility = new IdentityHashMap<>();
        final Matrix4fc[] matrices;
        final Transformation[] transformations;
        final boolean[] visibility;
        final List<Map<String, Boolean>> attachmentVisibility = new ArrayList<>();
        final Map<String, Map<String, CompoundTag>> nbt = new HashMap<>();
        final Map<String, Map<String, Set<String>>> nbtRemoved = new HashMap<>();

        private Pose(long generation, int count) {
            this.generation = generation;
            this.matrices = new Matrix4fc[count];
            this.transformations = new Transformation[count];
            this.visibility = new boolean[count];
        }
    }

    void calculatePose(Pose pose, Matrix4fc rootMatrix, Map<String, Matrix4f> transitionStart, float progress) {
        boolean prepareTransition = transitionStart != null && (transitionStart != this.transitionInput || pose.generation != this.transitionGeneration);
        for (int index = 0; index < this.nodeIds.size(); index++) {
            Node node = this.animation.node(index);
            Matrix4f world = this.matrices[index];
            int parentIndex = this.animation.parentIndex(index);
            if (transitionStart == null || prepareTransition) {
                this.local.identity();
                Quaternionf orientation = this.orientations[index].identity();
                for (int operationIndex = 0; operationIndex < node.transform().size(); operationIndex++) {
                    Operation operation = node.transform().get(operationIndex);
                    Track track = this.tracks[index][operationIndex];
                    boolean rotation = operation.op() == OperationType.ROTATE_EULER || operation.op() == OperationType.ROTATE_QUATERNION;
                    if (track == null) {
                        this.local.mul(this.animation.operationMatrix(operation));
                        if (rotation) orientation.mul(this.animation.operationRotation(operation)).normalize();
                    } else if (rotation) {
                        PreparedAnimation.operationRotation(operation, pose.values.get(track), this.operationRotation, this.quaternion);
                        this.local.mul(this.operationMatrix.rotation(this.operationRotation));
                        orientation.mul(this.operationRotation).normalize();
                    } else {
                        this.local.mul(PreparedAnimation.operationMatrix(operation, pose.values.get(track), this.operationMatrix));
                    }
                }
                if (parentIndex < 0) world.set(this.local);
                else {
                    Matrix4f parent = this.matrices[parentIndex];
                    Quaternionf parentOrientation = this.orientations[parentIndex];
                    if (node.inherit().rotation() == RotationInheritance.PARENT && node.inherit().scale()) {
                        world.set(parent).mul(this.local);
                    } else {
                        this.inherited.identity();
                        if (node.inherit().rotation() == RotationInheritance.PARENT) this.inherited.rotation(parentOrientation);
                        if (node.inherit().scale()) {
                            this.residual.rotation(parentOrientation).invert().mul(this.parentLinear.set(parent).setTranslation(0, 0, 0));
                            this.inherited.mul(this.residual);
                        }
                        world.set(this.inherited.setTranslation(parent.m30(), parent.m31(), parent.m32())).mul(this.local);
                    }
                    if (node.inherit().rotation() == RotationInheritance.PARENT) orientation.premul(parentOrientation).normalize();
                }
                if (!world.isFinite()) throw new IllegalStateException("Node " + nodeId(index) + " produced a non-finite transform");
            }
            Matrix4fc matrix = world;
            if (transitionStart != null) {
                if (prepareTransition) {
                    this.transitionStarts[index] = new Transformation(new Matrix4f(transitionStart.get(nodeId(index))));
                    this.transitionEnds[index] = new Transformation(new Matrix4f(world));
                }
                matrix = this.transitionStarts[index].slerp(this.transitionEnds[index], progress).getMatrix();
            }
            this.poseMatrices[index] = matrix;
            this.displayMatrix.set(rootMatrix).mul(matrix);
            Transformation previous = this.transformations[index];
            if (previous == null || !previous.getMatrix().equals(this.displayMatrix)) {
                // Transformation retains its matrix and Display retains its decomposed components.
                var transformation = new Transformation(new Matrix4f(this.displayMatrix));
                transformation.translation();
                transformation.leftRotation();
                transformation.scale();
                transformation.rightRotation();
                this.transformations[index] = transformation;
            }
            boolean visible = pose.sampledVisibility.getOrDefault(this.nodeVisibilityTracks[index], node.visible());
            if (parentIndex >= 0 && node.inherit().visibility()) visible &= this.visibility[parentIndex];
            this.visibility[index] = visible;
            Map<String, Boolean> attachments = this.attachmentVisibility.get(index);
            for (String attachmentId : this.animation.attachmentIds(index)) {
                attachments.put(attachmentId, pose.sampledVisibility.getOrDefault(
                    this.attachmentVisibilityTracks.get(index).get(attachmentId), node.attachments().get(attachmentId).visible()));
            }
            pose.matrices[index] = new Matrix4f(matrix);
            pose.transformations[index] = this.transformations[index];
            pose.visibility[index] = visible;
            pose.attachmentVisibility.add(Map.copyOf(attachments));
        }
        this.transitionInput = transitionStart;
        this.transitionGeneration = pose.generation;
    }

    void finishFrame() {
        evaluateNbt(this.sampleTick);
    }

    private void evaluateNbt(double tick) {
        this.sampleProgress = 0;
        this.session.setQuery("key_frame_lerp_time", 0);
        for (String nodeId : this.nodeIds) {
            Map<String, Track> tracks = this.nbtTracks.getOrDefault(nodeId, Map.of());
            Map<String, CompoundTag> states = this.nbt.computeIfAbsent(nodeId, ignored -> new LinkedHashMap<>());
            Map<String, Set<String>> removedStates = this.nbtRemoved.computeIfAbsent(nodeId, ignored -> new HashMap<>());
            for (String attachmentId : tracks.keySet()) {
                CompoundTag state = new CompoundTag();
                Set<String> removed = new HashSet<>();
                for (Keyframe key : tracks.get(attachmentId).driver().keys()) {
                    if (key.tick() > tick) break;
                    NbtValue patch = (NbtValue) key.post();
                    List<String> remove = patch instanceof ConstantNbtValue constant ? constant.remove() : ((MolangNbtValue) patch).remove();
                    remove.forEach(state::remove);
                    removed.addAll(remove);
                    CompoundTag merge;
                    if (patch instanceof ConstantNbtValue constant) merge = constant.value();
                    else {
                        MolangNbtValue molang = (MolangNbtValue) patch;
                        merge = this.nbtCaptures.get(molang.path());
                        if (merge == null) {
                            try {
                                merge = TagParser.parseCompoundFully(this.session.evaluateString(this.animation.expression(molang.path())));
                            } catch (Exception exception) {
                                throw new IllegalStateException(molang.path() + " must return valid compound SNBT", exception);
                            }
                            for (String field : merge.keySet()) {
                                if (RUNTIME_NBT_FIELDS.contains(field)) throw new IllegalStateException(molang.path() + ": runtime-owned field " + field);
                            }
                            this.nbtCaptures.put(molang.path(), merge);
                        }
                    }
                    state.merge(merge);
                }
                states.put(attachmentId, state);
                removedStates.put(attachmentId, removed);
            }
        }
    }

    private boolean sampleVisibility(Track track, double tick, boolean base) {
        if (track == null) return base;
        Driver driver = track.driver();
        VisibilityValue value;
        if (driver.type() == DriverType.EXPRESSION) value = (VisibilityValue) driver.value();
        else {
            List<Keyframe> keys = driver.keys();
            if (tick < keys.getFirst().tick()) {
                if (!driver.firstPre()) return base;
                value = (VisibilityValue) keys.getFirst().pre();
            } else {
                int index = keyIndex(driver, tick);
                value = (VisibilityValue) keys.get(index).post();
            }
        }
        return switch (value) {
            case ConstantVisibility constant -> constant.value();
            case MolangVisibility molang -> scalar(new MolangValue(molang.source(), molang.path()), 0) != 0;
        };
    }

    private double[] sample(Driver driver, double tick, List<Double> base) {
        if (driver == null) return this.animation.baseValue(base);
        if (driver.type() == DriverType.EXPRESSION) return vector((VectorValue) driver.value(), 0);
        List<Keyframe> keys = driver.keys();
        if (tick < keys.getFirst().tick()) return driver.firstPre() ? vector((VectorValue) keys.getFirst().pre(), 0) : this.animation.baseValue(base);
        int index = keyIndex(driver, tick);
        if (driver.atKey() == KeyPolicy.SEGMENT && keys.size() > 1) index = Math.min(index, keys.size() - 2);
        Keyframe left = keys.get(index);
        if (driver.atKey() != KeyPolicy.SEGMENT && tick == left.tick()) return vector((VectorValue) (driver.atKey() == KeyPolicy.PRE ? left.pre() : left.post()), 1);
        if (index == keys.size() - 1) return vector((VectorValue) left.post(), 1);
        Keyframe right = keys.get(index + 1);
        double progress = Math.min(1, (tick - left.tick()) / (right.tick() - left.tick()));
        Segment segment = driver.segments().get(index);
        VectorValue startValues = (VectorValue) left.post(), endValues = (VectorValue) right.pre();
        if (segment.interpolation() == Interpolation.STEP && driver.atKey() != KeyPolicy.SEGMENT) return vector(startValues, progress);
        boolean component = driver.componentEvaluation();
        double[] start = component ? null : vector(startValues, progress);
        double[] end = component ? null : vector(endValues, progress);
        if (driver.atKey() == KeyPolicy.SEGMENT && progress == 1) return component ? vector(endValues, progress) : end;
        if (segment.interpolation() == Interpolation.STEP || driver.atKey() == KeyPolicy.SEGMENT && segment.interpolation() == Interpolation.BEZIER && progress == 0) return component ? vector(startValues, progress) : start;
        double eased = component ? progress : easing(segment.easing(), progress);
        if (segment.interpolation() == Interpolation.SLERP) {
            Quaterniond rotation = this.slerpStart.set(start[0], start[1], start[2], start[3]).normalize()
                .slerp(this.slerpEnd.set(end[0], end[1], end[2], end[3]).normalize(), eased).normalize();
            return new double[]{rotation.x, rotation.y, rotation.z, rotation.w};
        }
        double[] previous = !component && segment.interpolation() == Interpolation.CATMULL_ROM && segment.previous() != null ? vector(segment.previous(), progress) : start;
        double[] following = !component && segment.interpolation() == Interpolation.CATMULL_ROM && segment.following() != null ? vector(segment.following(), progress) : end;
        double[] out = !component && segment.interpolation() == Interpolation.HERMITE ? vector(segment.outTangent(), progress) : null;
        double[] in = !component && segment.interpolation() == Interpolation.HERMITE ? vector(segment.inTangent(), progress) : null;
        double durationSeconds = (right.tick() - left.tick()) / 20.0;
        double[] result = new double[startValues.components().size()];
        for (int i = 0; i < result.length; i++) {
            double t = component ? easing(segment.easing(), progress) : eased;
            double p0 = component && segment.interpolation() == Interpolation.CATMULL_ROM && segment.previous() != null ? scalar(segment.previous().components().get(i), progress) : previous == null ? 0 : previous[i];
            double a = component ? scalar(startValues.components().get(i), progress) : start[i];
            double b = component ? scalar(endValues.components().get(i), progress) : end[i];
            double p1 = component && segment.interpolation() == Interpolation.CATMULL_ROM && segment.following() != null ? scalar(segment.following().components().get(i), progress) : following == null ? 0 : following[i];
            result[i] = switch (segment.interpolation()) {
                case LINEAR -> a + (b - a) * t;
                case CATMULL_ROM, HERMITE -> {
                    double m0 = segment.interpolation() == Interpolation.HERMITE ? (component ? scalar(segment.outTangent().components().get(i), progress) : out[i]) * durationSeconds : segment.tension() * (b - (segment.previous() == null ? a : p0));
                    double m1 = segment.interpolation() == Interpolation.HERMITE ? (component ? scalar(segment.inTangent().components().get(i), progress) : in[i]) * durationSeconds : segment.tension() * ((segment.following() == null ? b : p1) - a);
                    double t2 = t * t, t3 = t2 * t;
                    yield (2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * m0
                        + (-2 * t3 + 3 * t2) * b + (t3 - t2) * m1;
                }
                case BEZIER -> {
                    BezierHandle handle = segment.handles().get(i);
                    double outTime, inTime, outValue, inValue;
                    if (segment.inControlFirst()) {
                        inValue = scalar(handle.inValue(), progress);
                        inTime = scalar(handle.inTime(), progress);
                        outValue = scalar(handle.outValue(), progress);
                        outTime = scalar(handle.outTime(), progress);
                    } else {
                        outTime = scalar(handle.outTime(), progress);
                        inTime = scalar(handle.inTime(), progress);
                        if (!handle.outRescale() && !(outTime >= 0 && outTime <= 1) || !handle.inRescale() && !(inTime >= 0 && inTime <= 1)) throw new IllegalStateException("Bezier time handles must be within [0, 1]");
                        outValue = scalar(handle.outValue(), progress);
                        inValue = scalar(handle.inValue(), progress);
                    }
                    if (handle.outRescale()) { double clamped = Math.clamp(outTime, 0, 1); outValue /= 1 + Math.abs(clamped - outTime); outTime = clamped; }
                    if (handle.inRescale()) { double clamped = Math.clamp(inTime, 0, 1); inValue /= 1 + Math.abs(clamped - inTime); inTime = clamped; }
                    if (!(outTime >= 0 && outTime <= 1 && inTime >= 0 && inTime <= 1)) throw new IllegalStateException("Bezier time handles must be within [0, 1]");
                    double h1 = outValue + (handle.outRelative() ? a : 0);
                    double h2 = inValue + (handle.inRelative() ? b : 0);
                    double low = 0, high = 1;
                    for (int iteration = 0; iteration < 48; iteration++) {
                        double k = (low + high) / 2;
                        double x = cubic(0, outTime, inTime, 1, k);
                        if (x == t) { low = high = k; break; }
                        if (x < t) low = k;
                        else high = k;
                    }
                    yield cubic(a, h1, h2, b, (low + high) / 2);
                }
                default -> throw new IllegalStateException("Unexpected interpolation " + segment.interpolation());
            };
        }
        return result;
    }

    private int keyIndex(Driver driver, double tick) {
        int[] cursor = this.keyCursors.computeIfAbsent(driver, ignored -> new int[1]);
        List<Keyframe> keys = driver.keys();
        int index = cursor[0];
        if (keys.get(index).tick() > tick) {
            int low = 0, high = index;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (keys.get(middle).tick() <= tick) low = middle;
                else high = middle - 1;
            }
            index = low;
        } else {
            while (index + 1 < keys.size() && keys.get(index + 1).tick() <= tick) index++;
        }
        cursor[0] = index;
        return index;
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double s = 1 - t;
        return s * s * s * a + 3 * s * s * t * b + 3 * s * t * t * c + t * t * t * d;
    }

    private double easing(Easing easing, double u) {
        if (easing == null || easing.kernel().equals("linear")) return u;
        double parameter = scalar(easing.parameter(), u);
        if (easing.kernel().equals("power") && parameter <= 0
            || Set.of("steps", "blockbench_steps").contains(easing.kernel()) && (parameter < 2 || parameter != Math.floor(parameter))) throw new IllegalStateException("Invalid easing parameter");
        return switch (easing.direction()) {
            case "in" -> easingBase(easing.kernel(), parameter, u);
            case "out" -> 1 - easingBase(easing.kernel(), parameter, 1 - u);
            case "in_out" -> u < 0.5 ? easingBase(easing.kernel(), parameter, 2 * u) / 2 : 1 - easingBase(easing.kernel(), parameter, 2 - 2 * u) / 2;
            default -> throw new IllegalStateException("Unexpected easing direction " + easing.direction());
        };
    }

    private static double easingBase(String kernel, double parameter, double u) {
        return switch (kernel) {
            case "power" -> Math.pow(u, parameter);
            case "sine" -> 1 - Math.cos(Math.PI * u / 2);
            case "expo" -> u == 0 ? 0 : Math.pow(2, 10 * (u - 1));
            case "blockbench_expo" -> Math.pow(2, 10 * (u - 1));
            case "circ" -> 1 - Math.sqrt(1 - u * u);
            case "back" -> u * u * ((parameter + 1) * u - parameter);
            case "blockbench_elastic" -> 1 - Math.pow(Math.cos(Math.PI * u / 2), 3) * Math.cos(Math.PI * parameter * u);
            case "blockbench_bounce" -> Math.min(Math.min(121.0 / 16 * u * u, 121.0 / 4 * parameter * Math.pow(u - 6.0 / 11, 2) + 1 - parameter),
                Math.min(121 * parameter * parameter * Math.pow(u - 9.0 / 11, 2) + 1 - parameter * parameter,
                    484 * parameter * parameter * parameter * Math.pow(u - 10.5 / 11, 2) + 1 - parameter * parameter * parameter));
            case "steps" -> Math.floor(u * parameter) / parameter;
            case "blockbench_steps" -> {
                double step = 1 / parameter;
                if (u > (parameter - 1) * step) yield (parameter - 1) * step;
                double low = 0, high = parameter - 1;
                while (high - low > 1) {
                    double middle = low + Math.floor((high - low) / 2);
                    if (u >= middle * step) low = middle;
                    else high = middle;
                }
                yield low * step;
            }
            default -> throw new IllegalStateException("Unexpected easing kernel " + kernel);
        };
    }

    private double[] vector(VectorValue value, double progress) {
        double[] constant = this.animation.constantVector(value);
        if (constant != null) {
            if (constant.length > 0) {
                this.sampleProgress = progress;
                this.session.setQuery("key_frame_lerp_time", progress);
            }
            return constant;
        }
        double[] result = new double[value.components().size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = scalar(value.components().get(i), progress);
        }
        return result;
    }

    private double scalar(ScalarValue value, double progress) {
        this.sampleProgress = progress;
        this.session.setQuery("key_frame_lerp_time", progress);
        double result = switch (value) {
            case ConstantValue constant -> constant.value();
            case MolangValue molang -> this.session.evaluate(this.animation.expression(molang.path()));
        };
        if (!Double.isFinite(result)) throw new IllegalStateException("Transform component evaluated to a non-finite value");
        return result;
    }

    private double sharedValue(String id, double component) {
        SharedValue definition = this.animation.model().clip().values().get(id);
        if (definition == null) throw new IllegalStateException("Unknown shared value " + id);
        if (component != Math.floor(component) || component < 0 || component >= definition.base().size()) throw new IllegalStateException("Invalid component for shared value " + id);
        double[] value = this.sharedValues.get(id);
        if (value == null) {
            if (!this.evaluatingValues.add(id)) throw new IllegalStateException("Cyclic shared value " + id);
            double progress = this.sampleProgress;
            try {
                value = sample(definition.driver(), Math.addExact(this.sampleTick, definition.timeOffsetTicks()), definition.base());
                this.sharedValues.put(id, value);
            } finally {
                this.evaluatingValues.remove(id);
                this.sampleProgress = progress;
                this.session.setQuery("key_frame_lerp_time", progress);
            }
        }
        return value[(int) component];
    }
}
