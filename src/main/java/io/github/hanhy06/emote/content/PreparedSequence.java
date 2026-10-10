package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import io.github.hanhy06.emote.api.EmoteCallback;
import io.github.hanhy06.emote.api.EmoteLoadException;
import io.github.hanhy06.emote.skin.SkinBinding;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.*;

public final class PreparedSequence implements PreparedEmote {
    private final EmoteSequence model;
    private final Path sourcePath;
    private final List<Step> steps;
    private final Map<String, EmoteAnimation.Node> nodes;
    private final Map<String, Map<String, DisplayData>> displayContents;
    private final Map<String, Matrix4f> defaultMatrices;
    private final List<String> nodeOrder;
    private final List<SkinBinding> skinBindings;
    private final @Nullable Integer duration;
    private final float rotationDeadzone;

    private PreparedSequence(LoadedSequence loaded, List<Step> steps) {
        this.model = loaded.model();
        this.sourcePath = loaded.sourcePath();
        this.steps = List.copyOf(steps);
        PreparedAnimation first = steps.stream().filter(AnimationStep.class::isInstance).map(AnimationStep.class::cast)
            .flatMap(step -> step.candidates().stream()).filter(AnimationChoice.class::isInstance)
            .map(AnimationChoice.class::cast).map(AnimationChoice::animation).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Sequence must reference at least one animation"));
        this.skinBindings = first.skinBindings();
        this.rotationDeadzone = first.rotationDeadzone();
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        Map<String, Map<String, DisplayData>> contents = new LinkedHashMap<>();
        Map<String, Matrix4f> matrices = new LinkedHashMap<>();
        for (Step step : steps) {
            if (!(step instanceof AnimationStep animationStep)) continue;
            for (Choice choice : animationStep.candidates()) {
                if (!(choice instanceof AnimationChoice selected)) continue;
                PreparedAnimation animation = selected.animation();
                if (!this.skinBindings.equals(animation.skinBindings())) throw new IllegalArgumentException("Sequence animations must use the same skin layout: " + first.id() + " and " + animation.id());
                Map<String, Matrix4f> worldMatrices = new HashMap<>();
                for (String id : animation.nodeOrder()) {
                    EmoteAnimation.Node node = animation.nodes().get(id);
                    EmoteAnimation.Node existing = nodes.putIfAbsent(id, node);
                    if (existing != null && !existing.attachments().equals(node.attachments())) throw new IllegalArgumentException("Sequence animations must use compatible nodes: " + first.id() + " and " + animation.id());
                    contents.putIfAbsent(id, animation.displayContents().get(id));
                    Matrix4f world = new Matrix4f(animation.defaultMatrix(id));
                    if (node.parentId() != null) world.set(worldMatrices.get(node.parentId())).mul(animation.defaultMatrix(id));
                    worldMatrices.put(id, world);
                    matrices.putIfAbsent(id, world);
                }
            }
        }
        this.nodes = Collections.unmodifiableMap(nodes);
        this.displayContents = Map.copyOf(contents);
        this.defaultMatrices = Map.copyOf(matrices);
        this.nodeOrder = List.copyOf(nodes.keySet());
        this.duration = fixedDuration(steps);
    }

    public static PreparedSequence prepare(EmoteSequence model, Map<String, PreparedAnimation> animations) throws EmoteLoadException {
        return prepare(new LoadedSequence(Path.of("api", model.id().getNamespace(), model.id().getPath() + ".json"), model), animations);
    }

    public static PreparedSequence prepare(LoadedSequence loaded, Map<String, PreparedAnimation> animations) throws EmoteLoadException {
        try {
            return new PreparedSequence(loaded, resolveSteps(loaded.model().steps(), animations));
        } catch (IllegalArgumentException exception) {
            throw new EmoteLoadException(loaded.sourcePath(), "$.steps", exception.getMessage(), exception);
        }
    }

    private static List<Step> resolveSteps(List<EmoteSequence.Step> sourceSteps, Map<String, PreparedAnimation> animations) {
        List<Step> resolvedSteps = new ArrayList<>(sourceSteps.size());
        for (EmoteSequence.Step sourceStep : sourceSteps) {
            if (sourceStep instanceof EmoteSequence.WaitStep(int ticks)) {
                resolvedSteps.add(new WaitStep(ticks));
                continue;
            }
            EmoteSequence.AnimationStep step = (EmoteSequence.AnimationStep) sourceStep;
            List<Choice> candidates = new ArrayList<>(step.choices().size());
            for (EmoteSequence.Choice choice : step.choices()) {
                EmoteSequence.Control control = EmoteSequence.Control.fromId(choice.targetId());
                if (control != null) {
                    candidates.add(new ControlChoice(control, choice.chance()));
                    continue;
                }
                PreparedAnimation animation = resolveAnimation(choice.targetId().toString(), animations);
                candidates.add(new AnimationChoice(animation, choice.chance()));
            }
            resolvedSteps.add(new AnimationStep(candidates, step.repeat(), step.transitionTicks()));
        }
        return List.copyOf(resolvedSteps);
    }

    private static PreparedAnimation resolveAnimation(String id, Map<String, PreparedAnimation> animations) {
        PreparedAnimation animation = animations.get(id);
        if (animation == null) {
            throw new IllegalArgumentException("Unknown or disabled animation: " + id);
        }
        if (animation.loopMode() == EmoteAnimation.LoopMode.SERVER_SYNC) {
            throw new IllegalArgumentException("Server-synchronized animation is not supported in a sequence: " + animation.id());
        }
        if (animation.loopMode() == EmoteAnimation.LoopMode.HOLD) {
            throw new IllegalArgumentException("Hold animation is not supported in a sequence: " + animation.id());
        }
        return animation;
    }


    private static @Nullable Integer fixedDuration(List<Step> steps) {
        long duration = 0;
        boolean previousPose = false;
        for (Step step : steps) {
            if (step instanceof WaitStep wait) {
                duration += wait.ticks();
                continue;
            }
            AnimationStep animationStep = (AnimationStep) step;
            if (animationStep.candidates().size() != 1 || !(animationStep.candidates().getFirst() instanceof AnimationChoice choice)) return null;
            var animation = choice.animation().model();
            var playback = animation.settings().playback();
            if (animation.timeline().clock() != null || !(playback.startDelay() instanceof EmoteAnimation.ConstantValue start)
                || !(playback.loopDelay() instanceof EmoteAnimation.ConstantValue delay)) return null;
            for (int repeat = 0; repeat < animationStep.repeat(); repeat++) {
                if (previousPose) duration += animationStep.transitionTicks();
                duration += (long) start.value() + animation.timeline().duration();
                if (repeat + 1 < animationStep.repeat() && playback.mode() == EmoteAnimation.LoopMode.LOOP) duration += (long) delay.value();
                previousPose = true;
            }
        }
        return Math.toIntExact(duration);
    }

    public EmoteSequence model() { return this.model; }
    public List<Step> steps() { return this.steps; }
    @Override public String id() { return this.model.id().toString(); }
    @Override public EmoteMetadata metadata() { return this.model.metadata(); }
    @Override public Path sourcePath() { return this.sourcePath; }
    @Override public boolean standalone() { return true; }
    @Override public EmotePlayerBehavior playerBehavior() { return this.model.settings().player(); }
    @Override public @Nullable Integer duration() { return this.duration; }
    @Override public int cooldown() { return this.model.settings().cooldownTicks(); }
    @Override public EmoteAnimation.LoopMode loopMode() { return EmoteAnimation.LoopMode.ONCE; }
    @Override public Map<String, EmoteAnimation.Node> nodes() { return this.nodes; }
    @Override public List<String> nodeOrder() { return this.nodeOrder; }
    @Override public Map<String, Map<String, DisplayData>> displayContents() { return this.displayContents; }
    @Override public List<SkinBinding> skinBindings() { return this.skinBindings; }
    @Override public List<EmoteCallback> callbacks() { return this.model.callbacks(); }
    @Override public float rotationDeadzone() { return this.rotationDeadzone; }
    @Override public int displayEntityCount() { return this.displayContents.values().stream().mapToInt(Map::size).sum(); }
    @Override public Matrix4fc defaultMatrix(String nodeId) {
        Matrix4f matrix = this.defaultMatrices.get(nodeId);
        if (matrix == null) throw new IllegalStateException("Missing default matrix for node: " + nodeId);
        return matrix;
    }

    public sealed interface Step permits AnimationStep, WaitStep {
    }

    public record AnimationStep(List<Choice> candidates, int repeat, int transitionTicks) implements Step {
        public AnimationStep {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("sequence emote candidates must not be empty");
            }
            if (candidates.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("candidates");
            }
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }

        public AnimationStep(List<Choice> candidates, int repeat) {
            this(candidates, repeat, 0);
        }
    }

    public record WaitStep(int ticks) implements Step {
        public WaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

    public sealed interface Choice permits AnimationChoice, ControlChoice {
        int chance();
    }

    public record AnimationChoice(PreparedAnimation animation, int chance) implements Choice {
        public AnimationChoice {
            Objects.requireNonNull(animation, "animation");
        }
    }

    public record ControlChoice(EmoteSequence.Control control, int chance) implements Choice {
        public ControlChoice {
            Objects.requireNonNull(control, "control");
        }
    }

}
