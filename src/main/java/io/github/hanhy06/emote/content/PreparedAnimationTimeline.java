package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.playback.molang.MolangEngine;
import net.minecraft.nbt.CompoundTag;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hanhy06.emote.api.animation.EmoteAnimation.*;
import static io.github.hanhy06.emote.playback.molang.MolangQueries.SUPPORTED_NAMES;

public final class PreparedAnimationTimeline {
    private static final Pattern QUERY_REFERENCE = Pattern.compile("(?i)\\b(?:q|query)\\.([a-z_][a-z0-9_]*)");
    private static final Pattern VARIABLE_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:v|variable)\\s*\\.[a-z_][a-z0-9_]*\\s*=(?!=)"
    );
    private static final Pattern QUERY_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:q|query)\\s*\\.[a-z_][a-z0-9_]*\\s*=(?!=)"
    );

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
        CompoundTag state = new CompoundTag();
        List<CompiledNbtKeyframe> compiled = new ArrayList<>(frames.size());
        for (NbtKeyframe frame : frames) {
            state.merge(frame.value());
            compiled.add(new CompiledNbtKeyframe(frame.tick(), state.copy()));
        }
        return List.copyOf(compiled);
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
        validateValueProgram(source, path);
        return compileProgram(source, path);
    }

    private static MolangEngine.CompiledExpression compileProgram(String source, String path) {
        if (source == null) {
            return null;
        }
        if (QUERY_ASSIGNMENT.matcher(source).find()) {
            throw new IllegalArgumentException(path + " must not assign queries");
        }
        validateQueries(source, path);
        try {
            return MolangEngine.INSTANCE.compile(source);
        } catch (MolangEngine.MolangCompileException exception) {
            throw new IllegalArgumentException(path + " contains invalid Molang", exception);
        }
    }

    private static void validateValueProgram(String source, String path) {
        if (VARIABLE_ASSIGNMENT.matcher(source).find()) {
            throw new IllegalArgumentException(path + " must not assign persistent variables");
        }
    }

    private static void validateQueries(String source, String path) {
        Matcher matcher = QUERY_REFERENCE.matcher(source);
        while (matcher.find()) {
            String query = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!SUPPORTED_NAMES.contains(query)) {
                throw new IllegalArgumentException(path + " references unsupported query " + query);
            }
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

    public record CompiledNbtKeyframe(int tick, CompoundTag value) {
        public CompiledNbtKeyframe {
            value = value.copy();
        }

        @Override
        public CompoundTag value() {
            return this.value.copy();
        }
    }

}
