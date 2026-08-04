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

final class PlaybackLoadTest {
    static final int INSTANCE_COUNT = 100;
    static final int GRID_SIZE = 10;
    static final int MIN_INITIAL_TICK = 80;
    static final int MAX_INITIAL_TICK = 175;
    private static final long RANDOM_SEED = 0xE607EL;

    private final PlaybackEntityController entityController;
    private @Nullable Session session;

    PlaybackLoadTest(PlaybackEntityController entityController) {
        this.entityController = Objects.requireNonNull(entityController, "entityController");
    }

    int start(ServerLevel level, Vec3 origin, float yaw, List<RegisteredEmote> emotes) {
        if (emotes.isEmpty()) {
            throw new IllegalArgumentException("At least one emote is required for a load test");
        }

        stop();
        Random random = new Random(RANDOM_SEED);
        List<RegisteredEmote> selection = createRandomizedSelection(emotes, random);
        List<LoadTestInstance> instances = new ArrayList<>(INSTANCE_COUNT);
        try {
            for (int index = 0; index < INSTANCE_COUNT; index++) {
                RegisteredEmote emote = selection.get(index);
                PlaybackNodes nodes = this.entityController.create(level, gridPosition(origin, index), yaw, emote);
                TimelinePlayer timeline = new TimelinePlayer(
                    emote.playbackPlan(),
                    nodes,
                    this.entityController
                );
                startAtInitialTick(timeline, emote.animation(), initialTick(random, index));
                this.entityController.add(level, nodes);
                try {
                    timeline.resumeInitialInterpolation();
                    instances.add(new LoadTestInstance(emote, nodes, timeline));
                } catch (RuntimeException exception) {
                    this.entityController.remove(level, nodes);
                    throw exception;
                }
            }
        } catch (RuntimeException exception) {
            removeInstances(level, instances);
            throw exception;
        }

        this.session = new Session(level, instances);
        return instances.size();
    }

    int stop() {
        Session current = this.session;
        if (current == null) {
            return 0;
        }

        this.session = null;
        removeInstances(current.level(), current.instances());
        return current.instances().size();
    }

    void stopId(String id) {
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
        if (current.instances().isEmpty()) {
            this.session = null;
        }
    }

    void tick() {
        Session current = this.session;
        if (current == null) {
            return;
        }

        Iterator<LoadTestInstance> iterator = current.instances().iterator();
        while (iterator.hasNext()) {
            LoadTestInstance instance = iterator.next();
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
                Emote.LOGGER.warn("Failed while running load-test emote {}", instance.emote.id(), exception);
                this.entityController.remove(current.level(), instance.nodes);
                iterator.remove();
            }
        }
        if (current.instances().isEmpty()) {
            this.session = null;
        }
    }

    static Vec3 gridPosition(Vec3 origin, int index) {
        int column = index % GRID_SIZE;
        int row = index / GRID_SIZE;
        double halfSpan = (GRID_SIZE - 1) / 2.0D;
        return origin.add(column - halfSpan, 0.0D, row - halfSpan);
    }

    static <T> List<T> createRandomizedSelection(List<T> values, Random random) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot select from an empty list");
        }

        List<T> deck = new ArrayList<>(values);
        List<T> selection = new ArrayList<>(INSTANCE_COUNT);
        while (selection.size() < INSTANCE_COUNT) {
            Collections.shuffle(deck, random);
            int copyCount = Math.min(deck.size(), INSTANCE_COUNT - selection.size());
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

    private void removeInstances(ServerLevel level, List<LoadTestInstance> instances) {
        for (LoadTestInstance instance : instances) {
            this.entityController.remove(level, instance.nodes);
        }
    }

    private record Session(ServerLevel level, List<LoadTestInstance> instances) {
    }

    private static final class LoadTestInstance {
        private final RegisteredEmote emote;
        private final PlaybackNodes nodes;
        private TimelinePlayer timeline;

        private LoadTestInstance(RegisteredEmote emote, PlaybackNodes nodes, TimelinePlayer timeline) {
            this.emote = emote;
            this.nodes = nodes;
            this.timeline = timeline;
        }
    }
}
