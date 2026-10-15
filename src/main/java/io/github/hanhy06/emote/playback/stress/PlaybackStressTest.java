package io.github.hanhy06.emote.playback.stress;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.playback.EmotePlayback;
import io.github.hanhy06.emote.playback.PlaybackScene;
import io.github.hanhy06.emote.playback.RootTransform;
import io.github.hanhy06.emote.playback.molang.MolangQuerySource;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

public final class PlaybackStressTest {
    public static final int DEFAULT_INSTANCE_COUNT = 50;
    public static final int MAX_INSTANCE_COUNT = 500;
    public static final int DEFAULT_PACKET_FANOUT = 50;
    public static final int MAX_PACKET_FANOUT = 500;
    static final int MIN_INITIAL_TICK = 80;
    static final int MAX_INITIAL_TICK = 175;
    private static final long RANDOM_SEED = 0xE607EL;

    private final StressTestPacketLoad packetLoad = StressTestPacketLoad.INSTANCE;
    private @Nullable Session session;
    private @Nullable Session frameSession;
    private List<StressTestInstance> frameInstances = List.of();
    private long preparationNanos;
    private long applicationNanos;

    public StartResult start(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> emotes,
        int durationTicks,
        int instanceCount,
        int packetFanout,
        @Nullable Map<PlayerSkinRegion, String> preparedSkin,
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

        Random random = new Random(RANDOM_SEED);
        List<PreparedAnimation> selection = createRandomizedSelection(emotes, random, instanceCount);
        return startSelection(level, origin, yaw, selection, durationTicks, packetFanout, preparedSkin, completion, random);
    }

    public StartResult startByDisplayCount(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> emotes,
        int durationTicks,
        int targetDisplayEntityCount,
        int packetFanout,
        @Nullable Map<PlayerSkinRegion, String> preparedSkin,
        Consumer<PlaybackStressTestReport> completion
    ) {
        if (emotes.isEmpty()) {
            throw new IllegalArgumentException("At least one emote is required for a stress test");
        }
        if (targetDisplayEntityCount < 1) {
            throw new IllegalArgumentException("Stress-test display count must be positive");
        }
        Random random = new Random(RANDOM_SEED);
        List<PreparedAnimation> selection = createDisplayLimitedSelection(emotes, random, targetDisplayEntityCount, PreparedAnimation::displayEntityCount);
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("No registered emote fits within the requested display count: " + targetDisplayEntityCount);
        }
        return startSelection(level, origin, yaw, selection, durationTicks, packetFanout, preparedSkin, completion, random);
    }

    private StartResult startSelection(
        ServerLevel level,
        Vec3 origin,
        float yaw,
        List<PreparedAnimation> selection,
        int durationTicks,
        int packetFanout,
        @Nullable Map<PlayerSkinRegion, String> preparedSkin,
        Consumer<PlaybackStressTestReport> completion,
        Random random
    ) {
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
        int instanceCount = selection.size();
        List<StressTestInstance> instances = new ArrayList<>(instanceCount);
        int displayEntityCount = 0;
        try {
            for (int index = 0; index < instanceCount; index++) {
                PreparedAnimation emote = selection.get(index);
                PlaybackScene scene = PlaybackScene.create(level, RootTransform.create(gridPosition(origin, index, instanceCount), yaw), emote);
                EmotePlayback playback = new EmotePlayback(emote, MolangQuerySource.EMPTY, random);
                instances.add(new StressTestInstance(emote, scene, playback, initialTick(random, index)));
                scene.applySkin(emote.skinBindings(), preparedSkin);
                displayEntityCount += scene.displayEntityCount();
            }
        } catch (RuntimeException exception) {
            instances.forEach(instance -> instance.close(this.packetLoad));
            this.packetLoad.stop();
            throw exception;
        }

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
        return new StartResult(instances.size(), displayEntityCount);
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
            current.instances.forEach(instance -> instance.close(this.packetLoad));
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
        return current.activeDisplayEntities;
    }

    public void stopById(String id) {
        Session current = this.session;
        if (current == null) {
            return;
        }

        current.instances.removeIf(instance -> {
            if (!instance.emote.id().equals(id)) {
                return false;
            }
            instance.close(this.packetLoad);
            current.activeDisplayEntities -= instance.scene.displayEntityCount();
            return true;
        });
    }

    public boolean prepareTick() {
        if (this.frameSession != null) throw new IllegalStateException("Previous stress-test tick has not finished");
        Session current = this.session;
        if (current == null) return false;
        this.frameSession = current;
        this.applicationNanos = 0;
        List<StressTestInstance> frames = new ArrayList<>();
        long started = System.nanoTime();
        Iterator<StressTestInstance> iterator = current.instances.iterator();
        while (iterator.hasNext()) {
            StressTestInstance instance = iterator.next();
            instance.frame = null;
            instance.frameError = null;
            instance.frameApplied = false;
            try {
                instance.scene.preparePlacement(instance.scene.root(), 0);
                if (instance.restart) {
                    instance.playback = new EmotePlayback(instance.emote, MolangQuerySource.EMPTY, RandomGenerator.getDefault());
                    instance.frame = instance.playback.prepareStart(instance.scene);
                    instance.restart = false;
                } else if (!instance.added) {
                    instance.frame = prepareInitialFrame(instance.playback, instance.scene, instance.emote.model(), instance.initialTick);
                } else {
                    instance.frame = instance.playback.prepareAdvance();
                }
                frames.add(instance);
            }
            catch (RuntimeException exception) {
                EmoteMod.LOGGER.warn("Failed to prepare stress test emote {}", instance.emote.id(), exception);
                instance.close(this.packetLoad);
                iterator.remove();
                current.activeDisplayEntities -= instance.scene.displayEntityCount();
                current.failedInstances++;
            }
        }
        this.preparationNanos = System.nanoTime() - started;
        this.frameInstances = List.copyOf(frames);
        return !frames.isEmpty();
    }

    public void calculateFrames() {
        for (StressTestInstance instance : this.frameInstances) {
            try { instance.frame.calculate(); }
            catch (RuntimeException exception) { instance.frameError = exception; }
        }
    }

    public void failFrames(RuntimeException exception) {
        for (StressTestInstance instance : this.frameInstances) instance.frameError = exception;
    }

    public void applyFrames() {
        long started = System.nanoTime();
        Session current = this.frameSession;
        for (StressTestInstance instance : this.frameInstances) {
            if (current != this.session || !instance.active || instance.frameError != null) {
                instance.frame.discard();
                if (current == this.session && instance.active) failInstance(current, instance, instance.frameError);
                continue;
            }
            try {
                instance.scene.applyPlacement();
                instance.frame.apply();
                instance.frameApplied = true;
                if (!instance.added) {
                    instance.scene.add();
                    this.packetLoad.register(current.level, instance.scene);
                    instance.added = true;
                }
            } catch (RuntimeException exception) {
                if (!instance.frameApplied) instance.frame.discard();
                failInstance(current, instance, exception);
            }
        }
        this.applicationNanos = System.nanoTime() - started;
        if (current != null && current == this.session && !current.runtimeStarted) {
            this.packetLoad.beginRuntime();
            current.runtimeStarted = true;
        }
    }

    private void failInstance(Session current, StressTestInstance instance, RuntimeException exception) {
        EmoteMod.LOGGER.warn("Failed to run stress test emote {}", instance.emote.id(), exception);
        instance.close(this.packetLoad);
        current.instances.remove(instance);
        current.activeDisplayEntities -= instance.scene.displayEntityCount();
        current.failedInstances++;
    }

    public void discardFrames() {
        for (StressTestInstance instance : this.frameInstances) {
            if (!instance.frameApplied) instance.frame.discard();
        }
        releaseFrames();
    }

    public void tick(long endTickStartedNanos, long waitNanos) {
        Session current = this.frameSession;
        if (current == null || current != this.session) {
            releaseFrames();
            return;
        }
        long emoteProcessingNanos = this.preparationNanos + this.applicationNanos + waitNanos;
        long networkProcessingNanos = 0L;
        try {
            for (StressTestInstance instance : this.frameInstances) {
                if (!instance.active || !instance.frameApplied) continue;
                try {
                    long emoteStartedNanos = System.nanoTime();
                    try {
                        instance.frame.dispatchEvents();
                        instance.restart = instance.frame.result() == EmotePlayback.AdvanceResult.FINISHED;
                    } finally {
                        emoteProcessingNanos += System.nanoTime() - emoteStartedNanos;
                    }

                    long networkStartedNanos = System.nanoTime();
                    try {
                        this.packetLoad.sync(instance.scene);
                    } finally {
                        networkProcessingNanos += System.nanoTime() - networkStartedNanos;
                    }
                } catch (RuntimeException exception) {
                    failInstance(current, instance, exception);
                }
            }
        } finally {
            current.recordProcessing(emoteProcessingNanos, networkProcessingNanos);
            releaseFrames();
        }
        this.packetLoad.sampleRuntimeTick();
        current.recordCompletedServerTick(System.nanoTime() - endTickStartedNanos);
        current.completedTicks++;
        if (current.completedTicks >= current.durationTicks) {
            PlaybackStressTestReport report = stop();
            if (report != null) {
                current.completion.accept(report);
            }
        }
    }

    private void releaseFrames() {
        for (StressTestInstance instance : this.frameInstances) instance.frame = null;
        this.frameInstances = List.of();
        this.frameSession = null;
    }

    static Vec3 gridPosition(Vec3 origin, int index, int instanceCount) {
        int gridSize = (int) Math.ceil(Math.sqrt(instanceCount));
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

    static <T> List<T> createDisplayLimitedSelection(
        List<T> values,
        Random random,
        int displayLimit,
        ToIntFunction<T> displayCount
    ) {
        if (values.isEmpty()) throw new IllegalArgumentException("Cannot select from an empty list");
        List<T> deck = new ArrayList<>(values);
        List<T> selection = new ArrayList<>();
        int selectedDisplays = 0;
        boolean added;
        do {
            Collections.shuffle(deck, random);
            added = false;
            for (T value : deck) {
                int valueDisplays = displayCount.applyAsInt(value);
                if (valueDisplays <= 0
                    || selection.size() >= MAX_INSTANCE_COUNT
                    || selectedDisplays + (long) valueDisplays > displayLimit) {
                    continue;
                }
                selection.add(value);
                selectedDisplays += valueDisplays;
                added = true;
            }
        } while (added && selection.size() < MAX_INSTANCE_COUNT);
        return List.copyOf(selection);
    }

    public record StartResult(int instanceCount, int displayEntityCount) {
    }

    static int initialTick(Random random, int index) {
        return switch (index) {
            case 0 -> MIN_INITIAL_TICK;
            case 1 -> MAX_INITIAL_TICK;
            default -> random.nextInt(MIN_INITIAL_TICK, MAX_INITIAL_TICK + 1);
        };
    }

    private EmotePlayback.Frame prepareInitialFrame(EmotePlayback playback, EmotePlayback.PoseTarget target, EmoteAnimation animation, int requestedTick) {
        int initialTick = requestedTick;
        if (animation.settings().playback().mode() == EmoteAnimation.PlaybackMode.ONCE
            || animation.settings().playback().mode() == EmoteAnimation.PlaybackMode.HOLD) {
            initialTick = Math.clamp(
                initialTick,
                0,
                Math.max(0, animation.clip().durationTicks() - 1)
            );
        }
        return playback.prepareStartAtCyclePhase(target, initialTick);
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
        private boolean runtimeStarted;
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

        private void recordProcessing(long emoteNanos, long networkNanos) {
            this.processingSamples++;
            this.emoteProcessingNanos += emoteNanos;
            this.maximumEmoteProcessingNanos = Math.max(this.maximumEmoteProcessingNanos, emoteNanos);
            this.networkProcessingNanos += networkNanos;
            this.maximumNetworkProcessingNanos = Math.max(this.maximumNetworkProcessingNanos, networkNanos);
        }

        private void recordCompletedServerTick(long stressTickNanos) {
            int completedServerTick = EmoteMod.SERVER.getTickCount();
            if (completedServerTick < this.startedServerTick || completedServerTick == this.lastSampledServerTick) {
                return;
            }

            long[] tickTimesNanos = EmoteMod.SERVER.getTickTimesNanos();
            long elapsedNanos = tickTimesNanos[Math.floorMod(completedServerTick, tickTimesNanos.length)] + stressTickNanos;
            this.lastSampledServerTick = completedServerTick;
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
                : nanosToMillis(percentile95(this.serverTickNanosSamples));
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

        private static long percentile95(List<Long> samples) {
            if (samples.isEmpty()) {
                return 0L;
            }

            List<Long> sorted = new ArrayList<>(samples);
            sorted.sort(Long::compareTo);
            int percentileIndex = Math.max(0, (int) Math.ceil(sorted.size() * 0.95D) - 1);
            return sorted.get(percentileIndex);
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0D;
        }
    }

    private static final class StressTestInstance {
        private final PreparedAnimation emote;
        private final PlaybackScene scene;

        private EmotePlayback playback;
        private final int initialTick;
        private EmotePlayback.@Nullable Frame frame;
        private @Nullable RuntimeException frameError;
        private boolean active = true, added, restart, frameApplied;

        private StressTestInstance(PreparedAnimation emote, PlaybackScene scene, EmotePlayback playback, int initialTick) {
            this.emote = emote;
            this.scene = scene;
            this.playback = playback;
            this.initialTick = initialTick;
        }

        private void close(StressTestPacketLoad packetLoad) {
            this.active = false;
            if (this.frame != null) this.frame.cancelEvents();
            try { packetLoad.unregister(this.scene); }
            finally { this.scene.close(); }
        }
    }
}
