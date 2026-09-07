package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.molang.MolangEngine;
import io.github.hanhy06.emote.molang.MolangQueryCatalog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;

public final class PreparedAnimationTimeline {
    private final List<String> nodeOrder;
    private final Map<String, CompiledNodeTracks> tracks;
    private final MolangEngine.CompiledExpression initialize;
    private final MolangEngine.CompiledExpression tick;
    private PreparedAnimationTimeline(
        List<String> nodeOrder,
        Map<String, CompiledNodeTracks> tracks,
        MolangEngine.CompiledExpression initialize,
        MolangEngine.CompiledExpression tick
    ) {
        this.nodeOrder = nodeOrder;
        this.tracks = tracks;
        this.initialize = initialize;
        this.tick = tick;
    }

    public static PreparedAnimationTimeline compile(EmoteAnimation animation) {
        Objects.requireNonNull(animation, "animation");
        List<String> order = topologicalOrder(animation.nodes());
        Map<String, CompiledNodeTracks> tracks = new LinkedHashMap<>();
        animation.timeline().tracks().forEach((nodeId, nodeTracks) -> tracks.put(nodeId, compile(nodeTracks)));
        return new PreparedAnimationTimeline(
            order,
            Map.copyOf(tracks),
            compileProgram(animation.molang().initialize(), "$.molang.initialize"),
            compileProgram(animation.molang().tick(), "$.molang.tick")
        );
    }

    public List<String> nodeOrder() {
        return this.nodeOrder;
    }

    public Map<String, CompiledNodeTracks> tracks() {
        return this.tracks;
    }

    public MolangEngine.CompiledExpression initialize() {
        return this.initialize;
    }

    public MolangEngine.CompiledExpression tick() {
        return this.tick;
    }

    private static CompiledNodeTracks compile(NodeTracks tracks) {
        return new CompiledNodeTracks(
            compileVectors(tracks.position()),
            compileVectors(tracks.rotation()),
            compileVectors(tracks.scale()),
            tracks.visible().stream()
                .map(frame -> new CompiledVisibilityKeyframe(frame.tick(), compile(frame.value())))
                .toList(),
            compileNbt(tracks.nbt())
        );
    }

    private static List<CompiledNbtKeyframe> compileNbt(List<NbtKeyframe> frames) {
        return frames.stream().map(frame -> switch (frame.value()) {
            case FixedNbtValue fixed -> new CompiledNbtKeyframe(
                frame.tick(),
                fixed.value(),
                null,
                null
            );
            case MolangNbtValue molang -> new CompiledNbtKeyframe(
                frame.tick(),
                null,
                compileValueProgram(molang.expression().source(), molang.expression().path()),
                molang.expression().path()
            );
        }).toList();
    }

    private static List<CompiledVectorKeyframe> compileVectors(List<VectorKeyframe> frames) {
        return frames.stream().map(frame -> new CompiledVectorKeyframe(
            frame.tick(),
            compile(frame.pre()),
            compile(frame.post()),
            frame.interpolation(),
            frame.easing()
        )).toList();
    }

    private static CompiledVector compile(VectorValue value) {
        return new CompiledVector(compile(value.x()), compile(value.y()), compile(value.z()));
    }

    private static CompiledScalar compile(ScalarValue value) {
        return switch (value) {
            case ConstantValue constant -> new CompiledScalar(constant.value(), null, null);
            case MolangValue molang -> new CompiledScalar(
                0.0D,
                compileValueProgram(molang.source(), molang.path()),
                molang.path()
            );
        };
    }

    private static CompiledScalar compile(VisibilityValue value) {
        return switch (value) {
            case ConstantVisibility constant -> new CompiledScalar(constant.value() ? 1.0D : 0.0D, null, null);
            case MolangVisibility molang -> new CompiledScalar(
                0.0D,
                compileValueProgram(molang.source(), molang.path()),
                molang.path()
            );
        };
    }

    private static MolangEngine.CompiledExpression compileValueProgram(String source, String path) {
        MolangEngine.CompiledExpression expression = compileProgram(source, path);
        if (expression.assignsPersistentVariables()) {
            throw new IllegalArgumentException(path + " must not assign persistent variables");
        }
        return expression;
    }

    private static MolangEngine.CompiledExpression compileProgram(String source, String path) {
        if (source == null) {
            return null;
        }
        try {
            MolangEngine.CompiledExpression expression = MolangEngine.INSTANCE.compile(source);
            MolangQueryCatalog.validate(expression, path);
            return expression;
        } catch (MolangEngine.MolangCompileException exception) {
            throw new IllegalArgumentException(path + " contains invalid Molang", exception);
        }
    }

    private static List<String> topologicalOrder(Map<String, Node> nodes) {
        List<String> result = new ArrayList<>(nodes.size());
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodes.keySet()) {
            visit(nodeId, nodes, visited, result);
        }
        return List.copyOf(result);
    }

    private static void visit(String nodeId, Map<String, Node> nodes, Set<String> visited, List<String> result) {
        if (!visited.add(nodeId)) {
            return;
        }
        Node node = Objects.requireNonNull(nodes.get(nodeId), "Missing node " + nodeId);
        if (node.parentId() != null) {
            visit(node.parentId(), nodes, visited, result);
        }
        result.add(nodeId);
    }

    public record CompiledNodeTracks(
        List<CompiledVectorKeyframe> position,
        List<CompiledVectorKeyframe> rotation,
        List<CompiledVectorKeyframe> scale,
        List<CompiledVisibilityKeyframe> visible,
        List<CompiledNbtKeyframe> nbt
    ) {
    }

    public record CompiledVectorKeyframe(
        int tick,
        CompiledVector pre,
        CompiledVector post,
        Interpolation interpolation,
        Easing easing
    ) {
    }

    public record CompiledVector(CompiledScalar x, CompiledScalar y, CompiledScalar z) {
        public void evaluate(MolangEngine.Session session, double[] target) {
            target[0] = this.x.evaluate(session);
            target[1] = this.y.evaluate(session);
            target[2] = this.z.evaluate(session);
        }
    }

    public record CompiledScalar(
        double constant,
        MolangEngine.CompiledExpression expression,
        String path
    ) {
        public double evaluate(MolangEngine.Session session) {
            double result = this.expression == null ? this.constant : session.evaluate(this.expression);
            if (!Double.isFinite(result)) {
                throw new IllegalStateException((this.path == null ? "constant" : this.path) + " evaluated to a non-finite value");
            }
            return result;
        }
    }

    public record CompiledVisibilityKeyframe(int tick, CompiledScalar value) {
    }

    public static final class CompiledNbtKeyframe {
        private final int tick;
        private final CompoundTag fixed;
        private final MolangEngine.CompiledExpression expression;
        private final String path;
        private final Map<String, CompoundTag> cache = new ConcurrentHashMap<>();

        private CompiledNbtKeyframe(
            int tick,
            CompoundTag fixed,
            MolangEngine.CompiledExpression expression,
            String path
        ) {
            this.tick = tick;
            this.fixed = fixed == null ? null : fixed.copy();
            this.expression = expression;
            this.path = path;
        }

        public int tick() {
            return this.tick;
        }

        public CompoundTag evaluate(MolangEngine.Session session) {
            if (this.fixed != null) return this.fixed.copy();

            String source;
            try {
                source = session.evaluateString(this.expression);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException(this.path + " must evaluate to compound SNBT", exception);
            }
            CompoundTag cached = this.cache.get(source);
            if (cached != null) return cached.copy();

            CompoundTag parsed;
            try {
                parsed = TagParser.parseCompoundFully(source);
            } catch (Exception exception) {
                throw new IllegalStateException(this.path + " evaluated to invalid compound SNBT", exception);
            }
            CompoundTag previous = this.cache.putIfAbsent(source, parsed.copy());
            return (previous == null ? parsed : previous).copy();
        }

        public String path() {
            return this.path;
        }
    }

}
