package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

final class PlaybackStressTest {
    static final int DEFAULT_INSTANCE_COUNT = 100;
    static final int MAX_INSTANCE_COUNT = 1_000;
    static final int MIN_INITIAL_TICK = 80;
    static final int MAX_INITIAL_TICK = 175;
    private static final long RANDOM_SEED = 0xE607EL;

    private final PlaybackEntityController entityController;

    private @Nullable Session session;

    PlaybackStressTest(PlaybackEntityController entityController) {
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    int start(ServerLevel level, Vec3 origin, float yaw, List<RegisteredEmote> emotes, int instanceCount) {
        if (emotes.isEmpty()) {
            throw new IllegalArgumentException("At least one emote is required for a stress test");
        }
        if (instanceCount < 1 || instanceCount > MAX_INSTANCE_COUNT) {
            throw new IllegalArgumentException("Stress-test instance count is out of range: " + instanceCount);
        }

        stop();
        long startedNanos = System.nanoTime();
        int startedServerTick = Emote.SERVER.getTickCount();
        long baselineTickNanos = Emote.SERVER.getAverageTickTimeNanos();
        float targetTps = Emote.SERVER.tickRateManager().tickrate();
        Random random = new Random(RANDOM_SEED);
        List<RegisteredEmote> selection = createRandomizedSelection(emotes, random, instanceCount);
        List<StressTestInstance> instances = new ArrayList<>(instanceCount);
        int displayEntityCount = 0;
        try {
            for (int index = 0; index < instanceCount; index++) {
                RegisteredEmote emote = selection.get(index);
                PlaybackNodes nodes = this.entityController.create(
                    level,
                    gridPosition(origin, index, instanceCount),
                    yaw,
                    emote
                );
                TimelinePlayer timeline = new TimelinePlayer(
                    emote.playbackPlan(),
                    nodes,
                    this.entityController
                );
                startAtInitialTick(timeline, emote.animation(), initialTick(random, index));
                this.entityController.add(level, nodes);
                try {
                    timeline.resumeInitialInterpolation();
                    instances.add(new StressTestInstance(emote, nodes, timeline));
                    displayEntityCount += (int) nodes.nodes().values().stream()
                        .filter(node -> !node.isAnchor())
                        .count();
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

    @Nullable PlaybackStressTestReport stop() {
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

    int displayEntityCount() {
        Session current = this.session;
        if (current == null) {
            return 0;
        }
        return current.instances().stream()
            .mapToInt(instance -> displayEntityCount(instance.nodes))
            .sum();
    }

    void stopById(String id) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        current.instances().removeIf(instance -> {
            if (!instance.emote.id().equals(id)) {
                return false;
            }
            this.entityController.remove(current.level(), instance.nodes);
            return true;
        });
    }

    void tick() {
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
                    TimelinePlayer.AdvanceResult result = advanceTimeline(instance.timeline);
                    if (result == TimelinePlayer.AdvanceResult.FINISHED) {
                        instance.timeline = new TimelinePlayer(
                            instance.emote.playbackPlan(),
                            instance.nodes,
                            this.entityController
                        );
                        instance.timeline.start();
                    }
                } catch (RuntimeException exception) {
                    Emote.LOGGER.warn("Failed while running stress-test emote {}", instance.emote.id(), exception);
                    this.entityController.remove(current.level(), instance.nodes);
                    iterator.remove();
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

    private void startAtInitialTick(TimelinePlayer timeline, EmoteAnimation animation, int requestedTick) {
        int initialTick = requestedTick;
        if (animation.timeline().loop() == EmoteAnimation.LoopMode.ONCE) {
            initialTick = Math.clamp(
                initialTick,
                0,
                Math.max(0, animation.timeline().durationTicks() - 1)
            );
        }
        timeline.startAtCyclePhase(initialTick);
    }

    private TimelinePlayer.AdvanceResult advanceTimeline(TimelinePlayer timeline) {
        TimelinePlayer.AdvanceResult result = timeline.advance();
        if (result == TimelinePlayer.AdvanceResult.LOOP_BOUNDARY) {
            result = timeline.continueAfterLoopEvent();
        }
        return result;
    }

    private void removeInstances(ServerLevel level, List<StressTestInstance> instances) {
        for (StressTestInstance instance : instances) {
            this.entityController.remove(level, instance.nodes);
        }
    }

    private static int displayEntityCount(PlaybackNodes nodes) {
        return (int) nodes.nodes().values().stream()
            .filter(node -> !node.isAnchor())
            .count();
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

        private void recordManagerCpu(long elapsedNanos) {
            this.managerCpuSamples++;
            this.managerCpuNanos += elapsedNanos;
            this.maximumManagerCpuNanos = Math.max(this.maximumManagerCpuNanos, elapsedNanos);
        }

        private void recordCompletedServerTick() {
            int completedTick = Emote.SERVER.getTickCount() - 1;
            if (completedTick < this.startedServerTick || completedTick == this.lastSampledServerTick) {
                return;
            }

            long[] tickTimesNanos = Emote.SERVER.getTickTimesNanos();
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
        private final RegisteredEmote emote;
        private final PlaybackNodes nodes;

        private TimelinePlayer timeline;

        private StressTestInstance(RegisteredEmote emote, PlaybackNodes nodes, TimelinePlayer timeline) {
            this.emote = emote;
            this.nodes = nodes;
            this.timeline = timeline;
        }
    }
}
