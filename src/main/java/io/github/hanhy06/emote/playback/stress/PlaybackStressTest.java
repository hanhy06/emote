package io.github.hanhy06.emote.playback.stress;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;

public final class PlaybackStressTest {
    public static final int DEFAULT_INSTANCE_COUNT = 100;
    public static final int MAX_INSTANCE_COUNT = 1_000;
    static final int MIN_INITIAL_TICK = 80;
    static final int MAX_INITIAL_TICK = 175;
    private static final long RANDOM_SEED = 0xE607EL;

    private final PlaybackEntityController entityController;

    private @Nullable Session session;

    public PlaybackStressTest(PlaybackEntityController entityController) {
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    public int start(ServerLevel level, Vec3 origin, float yaw, List<PreparedAnimation> emotes, int instanceCount) {
        if (emotes.isEmpty()) {
            throw new IllegalArgumentException("At least one emote is required for a stress test");
        }
        if (instanceCount < 1 || instanceCount > MAX_INSTANCE_COUNT) {
            throw new IllegalArgumentException("Stress-test instance count is out of range: " + instanceCount);
        }

        stop();
        long startedNanos = System.nanoTime();
        int startedServerTick = EmoteMod.SERVER.getTickCount();
        long baselineTickNanos = EmoteMod.SERVER.getAverageTickTimeNanos();
        float targetTps = EmoteMod.SERVER.tickRateManager().tickrate();
        Random random = new Random(RANDOM_SEED);
        List<PreparedAnimation> selection = createRandomizedSelection(emotes, random, instanceCount);
        List<StressTestInstance> instances = new ArrayList<>(instanceCount);
        int displayEntityCount = 0;
        try {
            for (int index = 0; index < instanceCount; index++) {
                PreparedAnimation emote = selection.get(index);
                PlaybackNodes nodes = this.entityController.create(
                    level,
                    gridPosition(origin, index, instanceCount),
                    yaw,
                    emote
                );
                AnimationPlayer timeline = new AnimationPlayer(
                    emote,
                    nodes,
                    this.entityController
                );
                startAtInitialTick(timeline, emote.animation(), initialTick(random, index));
                this.entityController.add(level, nodes);
                try {
                    instances.add(new StressTestInstance(emote, nodes, timeline));
                    displayEntityCount += nodes.displayEntityCount();
                } catch (RuntimeException exception) {
                    this.entityController.remove(level, nodes);
                    throw exception;
                }
            }
        } catch (RuntimeException exception) {
            removeInstances(level, instances);
            throw exception;
        }

        long creationNanos = System.nanoTime() - startedNanos;
        this.session = new Session(
            level,
            instances,
            instanceCount,
            displayEntityCount,
            startedNanos,
            startedServerTick,
            baselineTickNanos,
            targetTps,
            creationNanos
        );
        return instances.size();
    }

    public @Nullable PlaybackStressTestReport stop() {
        Session current = this.session;
        if (current == null) {
            return null;
        }

        this.session = null;
        current.recordCompletedServerTick();
        long cleanupStartedNanos = System.nanoTime();
        removeInstances(current.level(), current.instances());
        long cleanupNanos = System.nanoTime() - cleanupStartedNanos;
        return current.createReport(cleanupNanos, System.nanoTime());
    }

    public int displayEntityCount() {
        Session current = this.session;
        if (current == null) {
            return 0;
        }
        return current.activeDisplayEntities();
    }

    public void stopById(String id) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        current.instances().removeIf(instance -> {
            if (!instance.emote.id().equals(id)) {
                return false;
            }
            this.entityController.remove(current.level(), instance.nodes);
            current.removeDisplayEntities(instance.nodes.displayEntityCount());
            return true;
        });
    }

    public void tick() {
        Session current = this.session;
        if (current == null) {
            return;
        }

        long managerStartedNanos = System.nanoTime();
        try {
            Iterator<StressTestInstance> iterator = current.instances().iterator();
            while (iterator.hasNext()) {
                StressTestInstance instance = iterator.next();
                try {
                    AnimationPlayer.AdvanceResult result = advanceTimeline(instance.timeline);
                    if (result == AnimationPlayer.AdvanceResult.FINISHED) {
                        instance.timeline = new AnimationPlayer(
                            instance.emote,
                            instance.nodes,
                            this.entityController
                        );
                        instance.timeline.start();
                    }
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.warn("Failed to run stress-test emote {}", instance.emote.id(), exception);
                    this.entityController.remove(current.level(), instance.nodes);
                    iterator.remove();
                    current.removeDisplayEntities(instance.nodes.displayEntityCount());
                    current.failedInstances++;
                }
            }
        } finally {
            current.recordManagerCpu(System.nanoTime() - managerStartedNanos);
        }
        current.recordCompletedServerTick();
    }

    static int gridSize(int instanceCount) {
        return (int) Math.ceil(Math.sqrt(instanceCount));
    }

    static Vec3 gridPosition(Vec3 origin, int index, int instanceCount) {
        int gridSize = gridSize(instanceCount);
        int column = index % gridSize;
        int row = index / gridSize;
        double halfSpan = (gridSize - 1) / 2.0D;
        return origin.add(column - halfSpan, 0.0D, row - halfSpan);
    }

    static <T> List<T> createRandomizedSelection(List<T> values, Random random, int instanceCount) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot select from an empty list");
        }

        List<T> deck = new ArrayList<>(values);
        List<T> selection = new ArrayList<>(instanceCount);
        while (selection.size() < instanceCount) {
            Collections.shuffle(deck, random);
            int copyCount = Math.min(deck.size(), instanceCount - selection.size());
            selection.addAll(deck.subList(0, copyCount));
        }
        return List.copyOf(selection);
    }

    static int initialTick(Random random, int index) {
        return switch (index) {
            case 0 -> MIN_INITIAL_TICK;
            case 1 -> MAX_INITIAL_TICK;
            default -> random.nextInt(MIN_INITIAL_TICK, MAX_INITIAL_TICK + 1);
        };
    }

    private void startAtInitialTick(AnimationPlayer timeline, EmoteAnimation animation, int requestedTick) {
        int initialTick = requestedTick;
        if (animation.settings().playback().mode() == EmoteAnimation.LoopMode.ONCE
            || animation.settings().playback().mode() == EmoteAnimation.LoopMode.HOLD) {
            initialTick = Math.clamp(
                initialTick,
                0,
                Math.max(0, animation.timeline().durationTicks() - 1)
            );
        }
        timeline.startAtCyclePhase(initialTick);
    }

    private AnimationPlayer.AdvanceResult advanceTimeline(AnimationPlayer timeline) {
        AnimationPlayer.AdvanceResult result = timeline.advance();
        if (result == AnimationPlayer.AdvanceResult.LOOP_BOUNDARY) {
            result = timeline.continueAfterLoopEvent();
        }
        return result;
    }

    private void removeInstances(ServerLevel level, List<StressTestInstance> instances) {
        for (StressTestInstance instance : instances) {
            this.entityController.remove(level, instance.nodes);
        }
    }

    static double estimatedTps(double mspt, double targetTps) {
        if (mspt <= 0.0D) {
            return targetTps;
        }
        return Math.min(targetTps, 1_000.0D / mspt);
    }

    private static final class Session {
        private final ServerLevel level;
        private final List<StressTestInstance> instances;
        private final int requestedInstances;
        private final int peakDisplayEntities;
        private final long startedNanos;
        private final int startedServerTick;
        private final long baselineTickNanos;
        private final float targetTps;
        private final long creationNanos;

        private int lastSampledServerTick = Integer.MIN_VALUE;
        private int activeDisplayEntities;
        private int failedInstances;
        private int serverTickSamples;
        private long serverTickNanos;
        private long maximumServerTickNanos;
        private int managerCpuSamples;
        private long managerCpuNanos;
        private long maximumManagerCpuNanos;

        private Session(
            ServerLevel level,
            List<StressTestInstance> instances,
            int requestedInstances,
            int peakDisplayEntities,
            long startedNanos,
            int startedServerTick,
            long baselineTickNanos,
            float targetTps,
            long creationNanos
        ) {
            this.level = level;
            this.instances = instances;
            this.requestedInstances = requestedInstances;
            this.peakDisplayEntities = peakDisplayEntities;
            this.activeDisplayEntities = peakDisplayEntities;
            this.startedNanos = startedNanos;
            this.startedServerTick = startedServerTick;
            this.baselineTickNanos = baselineTickNanos;
            this.targetTps = targetTps;
            this.creationNanos = creationNanos;
        }

        private ServerLevel level() {
            return this.level;
        }

        private List<StressTestInstance> instances() {
            return this.instances;
        }

        private int activeDisplayEntities() {
            return this.activeDisplayEntities;
        }

        private void removeDisplayEntities(int count) {
            this.activeDisplayEntities -= count;
        }

        private void recordManagerCpu(long elapsedNanos) {
            this.managerCpuSamples++;
            this.managerCpuNanos += elapsedNanos;
            this.maximumManagerCpuNanos = Math.max(this.maximumManagerCpuNanos, elapsedNanos);
        }

        private void recordCompletedServerTick() {
            int completedTick = EmoteMod.SERVER.getTickCount() - 1;
            if (completedTick < this.startedServerTick || completedTick == this.lastSampledServerTick) {
                return;
            }

            long[] tickTimesNanos = EmoteMod.SERVER.getTickTimesNanos();
            long elapsedNanos = tickTimesNanos[Math.floorMod(completedTick, tickTimesNanos.length)];
            this.lastSampledServerTick = completedTick;
            if (elapsedNanos <= 0L) {
                return;
            }
            this.serverTickSamples++;
            this.serverTickNanos += elapsedNanos;
            this.maximumServerTickNanos = Math.max(this.maximumServerTickNanos, elapsedNanos);
        }

        private PlaybackStressTestReport createReport(long cleanupNanos, long stoppedNanos) {
            double baselineMspt = nanosToMillis(this.baselineTickNanos);
            double averageMspt = this.serverTickSamples == 0
                ? baselineMspt
                : nanosToMillis(this.serverTickNanos) / this.serverTickSamples;
            double maximumMspt = this.serverTickSamples == 0
                ? baselineMspt
                : nanosToMillis(this.maximumServerTickNanos);
            double baselineTps = estimatedTps(baselineMspt, this.targetTps);
            double averageTps = estimatedTps(averageMspt, this.targetTps);
            double minimumTps = estimatedTps(maximumMspt, this.targetTps);
            double averageManagerCpuMillis = this.managerCpuSamples == 0
                ? 0.0D
                : nanosToMillis(this.managerCpuNanos) / this.managerCpuSamples;
            return new PlaybackStressTestReport(
                this.requestedInstances,
                this.instances.size(),
                this.peakDisplayEntities,
                this.failedInstances,
                this.serverTickSamples,
                (stoppedNanos - this.startedNanos) / 1_000_000_000.0D,
                nanosToMillis(this.creationNanos),
                nanosToMillis(cleanupNanos),
                baselineMspt,
                averageMspt,
                maximumMspt,
                baselineTps,
                averageTps,
                minimumTps,
                Math.max(0.0D, baselineTps - averageTps),
                averageManagerCpuMillis,
                nanosToMillis(this.maximumManagerCpuNanos)
            );
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0D;
        }
    }

    private static final class StressTestInstance {
        private final PreparedAnimation emote;
        private final PlaybackNodes nodes;

        private AnimationPlayer timeline;

        private StressTestInstance(PreparedAnimation emote, PlaybackNodes nodes, AnimationPlayer timeline) {
            this.emote = emote;
            this.nodes = nodes;
            this.timeline = timeline;
        }
    }
}
