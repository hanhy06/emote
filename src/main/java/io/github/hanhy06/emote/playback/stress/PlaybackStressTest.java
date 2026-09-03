package io.github.hanhy06.emote.playback.stress;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

public final class PlaybackStressTest {
    public static final int DEFAULT_INSTANCE_COUNT = 100;
    public static final int MAX_INSTANCE_COUNT = 500;
    public static final int DEFAULT_PACKET_FANOUT = 20;
    public static final int MAX_PACKET_FANOUT = 500;
    static final int MIN_INITIAL_TICK = 80;
    static final int MAX_INITIAL_TICK = 175;
    private static final long RANDOM_SEED = 0xE607EL;

    private final PlaybackEntityController entityController;
    private final StressTestPacketLoad packetLoad = StressTestPacketLoad.INSTANCE;

    private @Nullable Session session;

    public PlaybackStressTest(PlaybackEntityController entityController) {
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    public int start(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> emotes,
        int durationTicks,
        int instanceCount,
        int packetFanout,
        @Nullable PreparedPlayerSkin preparedSkin,
        Consumer<PlaybackStressTestReport> completion
    ) {
        if (emotes.isEmpty()) {
            throw new IllegalArgumentException("At least one emote is required for a stress test");
        }
        if (instanceCount < 1 || instanceCount > MAX_INSTANCE_COUNT) {
            throw new IllegalArgumentException("Stress-test instance count is out of range: " + instanceCount);
        }
        if (durationTicks < 1) {
            throw new IllegalArgumentException("Stress-test duration must be at least one tick");
        }
        if (packetFanout < 0 || packetFanout > MAX_PACKET_FANOUT) {
            throw new IllegalArgumentException("Stress-test packet fanout is out of range: " + packetFanout);
        }

        stop();
        this.packetLoad.start(EmoteMod.SERVER, packetFanout);
        long startedNanos = System.nanoTime();
        int startedServerTick = EmoteMod.SERVER.getTickCount();
        long baselineTickNanos = EmoteMod.SERVER.getAverageTickTimeNanos();
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
                this.entityController.applySkin(nodes, emote.skinBindings(), preparedSkin);
                this.entityController.add(level, nodes);
                try {
                    this.packetLoad.register(level, nodes);
                    instances.add(new StressTestInstance(emote, nodes, timeline));
                    displayEntityCount += nodes.displayEntityCount();
                } catch (RuntimeException exception) {
                    this.packetLoad.unregister(nodes);
                    this.entityController.remove(level, nodes);
                    throw exception;
                }
            }
        } catch (RuntimeException exception) {
            removeInstances(level, instances);
            this.packetLoad.stop();
            throw exception;
        }

        this.packetLoad.beginRuntime();
        long creationNanos = System.nanoTime() - startedNanos;
        this.session = new Session(
            level,
            instances,
            instanceCount,
            displayEntityCount,
            durationTicks,
            completion,
            startedNanos,
            startedServerTick,
            baselineTickNanos,
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
        StressTestPacketLoad.PacketLoadResult packetLoadResult = this.packetLoad.finishRuntime();
        long cleanupStartedNanos = System.nanoTime();
        try {
            removeInstances(current.level(), current.instances());
        } finally {
            this.packetLoad.stop();
        }
        long cleanupNanos = System.nanoTime() - cleanupStartedNanos;
        return current.createReport(packetLoadResult, cleanupNanos, System.nanoTime());
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
            this.packetLoad.unregister(instance.nodes);
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

        long stressTickStartedNanos = System.nanoTime();
        long emoteProcessingNanos = 0L;
        long networkProcessingNanos = 0L;
        try {
            Iterator<StressTestInstance> iterator = current.instances().iterator();
            while (iterator.hasNext()) {
                StressTestInstance instance = iterator.next();
                try {
                    long emoteStartedNanos = System.nanoTime();
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
                    } finally {
                        emoteProcessingNanos += System.nanoTime() - emoteStartedNanos;
                    }

                    long networkStartedNanos = System.nanoTime();
                    try {
                        this.packetLoad.sync(instance.nodes);
                    } finally {
                        networkProcessingNanos += System.nanoTime() - networkStartedNanos;
                    }
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.warn("Failed to run stress-test emote {}", instance.emote.id(), exception);
                    this.packetLoad.unregister(instance.nodes);
                    this.entityController.remove(current.level(), instance.nodes);
                    iterator.remove();
                    current.removeDisplayEntities(instance.nodes.displayEntityCount());
                    current.failedInstances++;
                }
            }
        } finally {
            current.recordProcessing(emoteProcessingNanos, networkProcessingNanos);
        }
        this.packetLoad.sampleRuntimeTick();
        current.recordCompletedServerTick(System.nanoTime() - stressTickStartedNanos);
        current.completedTicks++;
        if (hasCompletedDuration(current.completedTicks, current.durationTicks)) {
            PlaybackStressTestReport report = stop();
            if (report != null) {
                current.completion.accept(report);
            }
        }
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
            this.packetLoad.unregister(instance.nodes);
            this.entityController.remove(level, instance.nodes);
        }
    }

    static boolean hasCompletedDuration(int completedTicks, int durationTicks) {
        return completedTicks >= durationTicks;
    }

    static long measureCompletedTickNanos(int completedTick, long[] tickTimesNanos, long stressTickNanos) {
        return tickTimesNanos[Math.floorMod(completedTick, tickTimesNanos.length)] + stressTickNanos;
    }

    private static final class Session {
        private final ServerLevel level;
        private final List<StressTestInstance> instances;
        private final int requestedInstances;
        private final int peakDisplayEntities;
        private final int durationTicks;
        private final Consumer<PlaybackStressTestReport> completion;
        private final long startedNanos;
        private final int startedServerTick;
        private final long baselineTickNanos;
        private final long creationNanos;

        private int lastSampledServerTick = Integer.MIN_VALUE;
        private int activeDisplayEntities;
        private int completedTicks;
        private int failedInstances;
        private int serverTickSamples;
        private long serverTickNanos;
        private long maximumServerTickNanos;
        private final List<Long> serverTickNanosSamples = new ArrayList<>();
        private int processingSamples;
        private long emoteProcessingNanos;
        private long maximumEmoteProcessingNanos;
        private long networkProcessingNanos;
        private long maximumNetworkProcessingNanos;

        private Session(
            ServerLevel level,
            List<StressTestInstance> instances,
            int requestedInstances,
            int peakDisplayEntities,
            int durationTicks,
            Consumer<PlaybackStressTestReport> completion,
            long startedNanos,
            int startedServerTick,
            long baselineTickNanos,
            long creationNanos
        ) {
            this.level = level;
            this.instances = instances;
            this.requestedInstances = requestedInstances;
            this.peakDisplayEntities = peakDisplayEntities;
            this.durationTicks = durationTicks;
            this.completion = completion;
            this.activeDisplayEntities = peakDisplayEntities;
            this.startedNanos = startedNanos;
            this.startedServerTick = startedServerTick;
            this.baselineTickNanos = baselineTickNanos;
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

        private void recordProcessing(long emoteNanos, long networkNanos) {
            this.processingSamples++;
            this.emoteProcessingNanos += emoteNanos;
            this.maximumEmoteProcessingNanos = Math.max(this.maximumEmoteProcessingNanos, emoteNanos);
            this.networkProcessingNanos += networkNanos;
            this.maximumNetworkProcessingNanos = Math.max(this.maximumNetworkProcessingNanos, networkNanos);
        }

        private void recordCompletedServerTick(long stressTickNanos) {
            int completedTick = EmoteMod.SERVER.getTickCount();
            if (completedTick < this.startedServerTick || completedTick == this.lastSampledServerTick) {
                return;
            }

            long[] tickTimesNanos = EmoteMod.SERVER.getTickTimesNanos();
            long elapsedNanos = measureCompletedTickNanos(completedTick, tickTimesNanos, stressTickNanos);
            this.lastSampledServerTick = completedTick;
            if (elapsedNanos <= 0L) {
                return;
            }
            this.serverTickSamples++;
            this.serverTickNanos += elapsedNanos;
            this.maximumServerTickNanos = Math.max(this.maximumServerTickNanos, elapsedNanos);
            this.serverTickNanosSamples.add(elapsedNanos);
        }

        private PlaybackStressTestReport createReport(
            StressTestPacketLoad.PacketLoadResult packetLoad,
            long cleanupNanos,
            long stoppedNanos
        ) {
            double baselineMspt = nanosToMillis(this.baselineTickNanos);
            double averageMspt = this.serverTickSamples == 0
                ? baselineMspt
                : nanosToMillis(this.serverTickNanos) / this.serverTickSamples;
            double maximumMspt = this.serverTickSamples == 0
                ? baselineMspt
                : nanosToMillis(this.maximumServerTickNanos);
            double percentile95Mspt = this.serverTickSamples == 0
                ? baselineMspt
                : nanosToMillis(StressTestStatistics.percentile95(this.serverTickNanosSamples));
            double averageEmoteProcessingMillis = this.processingSamples == 0
                ? 0.0D
                : nanosToMillis(this.emoteProcessingNanos) / this.processingSamples;
            double averageNetworkProcessingMillis = this.processingSamples == 0
                ? 0.0D
                : nanosToMillis(this.networkProcessingNanos) / this.processingSamples;
            return new PlaybackStressTestReport(
                this.requestedInstances,
                this.instances.size(),
                this.peakDisplayEntities,
                this.failedInstances,
                this.completedTicks,
                (stoppedNanos - this.startedNanos) / 1_000_000_000.0D,
                nanosToMillis(this.creationNanos),
                nanosToMillis(cleanupNanos),
                baselineMspt,
                averageMspt,
                percentile95Mspt,
                maximumMspt,
                this.emoteProcessingNanos / 1_000_000_000.0D,
                averageEmoteProcessingMillis,
                nanosToMillis(this.maximumEmoteProcessingNanos),
                this.networkProcessingNanos / 1_000_000_000.0D,
                averageNetworkProcessingMillis,
                nanosToMillis(this.maximumNetworkProcessingNanos),
                packetLoad
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
