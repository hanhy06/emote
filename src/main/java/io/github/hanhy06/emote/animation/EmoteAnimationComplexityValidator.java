package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;

import java.nio.file.Path;
import java.util.List;

final class EmoteAnimationComplexityValidator {
    static final int MAX_NODE_COUNT = 48;
    static final int MAX_DISPLAY_NODE_COUNT = 32;
    static final int MAX_DURATION_TICKS = 20 * 60 * 10;
    static final int MAX_TRANSFORMS_PER_TICK = 24;
    static final int MAX_STATE_CHANGES_PER_TICK = 8;
    static final int MAX_COMMANDS_PER_TICK = 16;
    static final int MAX_TOTAL_COMMANDS = 4_096;

    void validate(EmoteAnimation.Loaded loaded) throws EmoteAnimationLoadException {
        EmoteAnimation animation = loaded.animation();
        Path sourcePath = loaded.sourcePath();
        int nodeCount = animation.nodes().size();
        if (nodeCount > MAX_NODE_COUNT) {
            throw error(sourcePath, "$.nodes", "must contain at most " + MAX_NODE_COUNT + " nodes");
        }

        long displayNodeCount = animation.nodes().values().stream()
            .filter(node -> !(node instanceof EmoteAnimation.AnchorNode))
            .count();
        if (displayNodeCount > MAX_DISPLAY_NODE_COUNT) {
            throw error(
                sourcePath,
                "$.nodes",
                "must contain at most " + MAX_DISPLAY_NODE_COUNT + " display nodes"
            );
        }

        EmoteAnimation.Timeline timeline = animation.timeline();
        if (timeline.durationTicks() > MAX_DURATION_TICKS) {
            throw error(
                sourcePath,
                "$.timeline.duration_ticks",
                "must not exceed " + MAX_DURATION_TICKS
            );
        }

        long transformCount = 0L;
        long stateChangeCount = 0L;
        for (EmoteAnimation.Keyframe keyframe : timeline.keyframes()) {
            transformCount += keyframe.nodeTransforms().size();
            stateChangeCount += keyframe.nodeStates().size();
        }

        long transformBudget = displayNodeCount + (long) timeline.durationTicks() * MAX_TRANSFORMS_PER_TICK;
        if (transformCount > transformBudget) {
            throw error(
                sourcePath,
                "$.timeline.keyframes",
                "contains " + transformCount + " transforms; maximum for this duration is " + transformBudget
            );
        }

        long stateChangeBudget = displayNodeCount + (long) timeline.durationTicks() * MAX_STATE_CHANGES_PER_TICK;
        if (stateChangeCount > stateChangeBudget) {
            throw error(
                sourcePath,
                "$.timeline.keyframes",
                "contains " + stateChangeCount + " state changes; maximum for this duration is " + stateChangeBudget
            );
        }

        validateCommands(timeline.events(), sourcePath);
    }

    private void validateCommands(EmoteAnimation.Events events, Path sourcePath) throws EmoteAnimationLoadException {
        long totalCommands = commandCount(events.start()) + commandCount(events.loop()) + commandCount(events.stop());
        requireCommandBoundaryLimit(events.start(), "$.timeline.events.start", sourcePath);
        requireCommandBoundaryLimit(events.loop(), "$.timeline.events.loop", sourcePath);
        requireCommandBoundaryLimit(events.stop(), "$.timeline.events.stop", sourcePath);

        int currentTick = -1;
        int commandsAtTick = 0;
        for (EmoteAnimation.TimelineEvent event : events.timeline()) {
            totalCommands += event.commands().size();
            if (event.tick() != currentTick) {
                currentTick = event.tick();
                commandsAtTick = 0;
            }
            commandsAtTick += event.commands().size();
            if (commandsAtTick > MAX_COMMANDS_PER_TICK) {
                throw error(
                    sourcePath,
                    "$.timeline.events.timeline",
                    "must execute at most " + MAX_COMMANDS_PER_TICK + " commands at one tick"
                );
            }
        }

        if (totalCommands > MAX_TOTAL_COMMANDS) {
            throw error(
                sourcePath,
                "$.timeline.events",
                "must contain at most " + MAX_TOTAL_COMMANDS + " commands"
            );
        }
    }

    private void requireCommandBoundaryLimit(
        List<EmoteAnimation.Event> events,
        String fieldPath,
        Path sourcePath
    ) throws EmoteAnimationLoadException {
        if (commandCount(events) > MAX_COMMANDS_PER_TICK) {
            throw error(
                sourcePath,
                fieldPath,
                "must execute at most " + MAX_COMMANDS_PER_TICK + " commands at once"
            );
        }
    }

    private long commandCount(List<EmoteAnimation.Event> events) {
        return events.stream().mapToLong(event -> event.commands().size()).sum();
    }

    private EmoteAnimationLoadException error(Path sourcePath, String fieldPath, String message) {
        return new EmoteAnimationLoadException(sourcePath, fieldPath, message);
    }
}
