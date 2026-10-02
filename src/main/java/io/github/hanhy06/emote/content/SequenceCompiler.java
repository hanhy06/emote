package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.PlaybackTimeline;
import io.github.hanhy06.emote.util.Sha256;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

final class SequenceCompiler {
    private SequenceCompiler() {
    }

    static PreparedAnimation compile(
        EmoteSequence sequence,
        Path sourcePath,
        List<PreparedSequence.SelectedStep> steps,
        PreparedAnimation layoutAnchor
    ) {
        List<PreparedAnimation.PlaybackSegment> playbackSegments = new ArrayList<>();
        List<PlaybackTimeline.Segment> timelineSegments = new ArrayList<>();
        Map<Integer, Set<String>> hiddenNodes = new HashMap<>();
        if (steps.isEmpty() || !(steps.getFirst() instanceof PreparedSequence.SelectedEmoteStep)) {
            hiddenNodes.put(0, nodesToHide(layoutAnchor.animation(), null));
        }
        long offset = 0L;
        boolean hasPreviousPose = false;
        for (PreparedSequence.SelectedStep selectedStep : steps) {
            if (selectedStep instanceof PreparedSequence.SelectedWaitStep(int ticks, int stepIndex)) {
                timelineSegments.add(new PlaybackTimeline.Segment(timelineSegments.size(), stepIndex, null,
                    PlaybackTimeline.Phase.WAIT, requireTick(offset, sequence), (long) requireTick(offset + ticks, sequence), null));
                offset += ticks;
                continue;
            }
            PreparedSequence.SelectedEmoteStep step = (PreparedSequence.SelectedEmoteStep) selectedStep;
            EmoteAnimation animation = step.animation().animation();
            int transitionStartTick = requireTick(offset, sequence);
            int transitionTicks = hasPreviousPose ? step.transitionTicks() : 0;
            int segmentOffset = requireTick(offset + transitionTicks, sequence);
            int segmentEndTick = requireTick(offset + transitionTicks + animation.timeline().durationTicks(), sequence);
            if (transitionTicks > 0) {
                timelineSegments.add(new PlaybackTimeline.Segment(timelineSegments.size(), step.stepIndex(), step.repeatIndex(),
                    PlaybackTimeline.Phase.TRANSITION, transitionStartTick, (long) segmentOffset, animation.id()));
            }
            timelineSegments.add(new PlaybackTimeline.Segment(timelineSegments.size(), step.stepIndex(), step.repeatIndex(),
                PlaybackTimeline.Phase.ANIMATION, segmentOffset,
                (long) segmentEndTick, animation.id()));
            playbackSegments.add(new PreparedAnimation.PlaybackSegment(
                transitionStartTick,
                segmentOffset,
                segmentEndTick,
                step.animation()
            ));
            hiddenNodes.put(segmentOffset, nodesToHide(layoutAnchor.animation(), animation));

            offset += transitionTicks + animation.timeline().durationTicks();
            if (step.loopDelayAfter() && animation.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP) {
                int delay = animation.settings().playback().loopDelayTicks();
                if (delay > 0) {
                    timelineSegments.add(new PlaybackTimeline.Segment(timelineSegments.size(), step.stepIndex(), step.repeatIndex(),
                        PlaybackTimeline.Phase.LOOP_DELAY, requireTick(offset, sequence),
                        (long) requireTick(offset + delay, sequence), null));
                    offset += delay;
                }
            }
            hasPreviousPose = true;
        }

        EmoteAnimation compiledAnimation = new EmoteAnimation(
            sequence.id(),
            sequence.metadata(),
            new EmoteAnimation.Settings(
                true,
                sequence.settings().cooldownTicks(),
                layoutAnchor.animation().settings().rotationDeadzone(),
                layoutAnchor.animation().settings().displayInterpolationTicks(),
                sequence.settings().player(),
                new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0, 0)
            ),
            EmoteAnimation.MolangPrograms.empty(),
            layoutAnchor.animation().nodes(),
            new EmoteAnimation.Timeline(
                Math.max(requireTick(offset, sequence), 1),
                Map.of(),
                EmoteAnimation.Events.empty()
            ), sequence.callbacks());
        LoadedAnimation loaded = new LoadedAnimation(sourcePath, fingerprint(sequence, steps),
            compiledAnimation, layoutAnchor.source().preparedDisplayData());
        PreparedAnimation preparedLayout = PreparedAnimation.from(loaded, layoutAnchor.skinBindings());
        if (timelineSegments.isEmpty()) {
            timelineSegments.add(new PlaybackTimeline.Segment(0, null, null, PlaybackTimeline.Phase.WAIT, 0, 1L, null));
        }
        return PreparedAnimation.sequence(preparedLayout, playbackSegments, hiddenNodes, timelineSegments);
    }

    private static Set<String> nodesToHide(EmoteAnimation layout, EmoteAnimation active) {
        Set<String> hiddenNodes = new LinkedHashSet<>(layout.nodes().keySet());
        if (active != null) {
            hiddenNodes.removeAll(active.nodes().keySet());
        }
        return Set.copyOf(hiddenNodes);
    }

    private static int requireTick(long tick, EmoteSequence sequence) {
        if (tick > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Compiled sequence is too long: " + sequence.id());
        }
        return (int) tick;
    }

    private static String fingerprint(
        EmoteSequence sequence,
        List<PreparedSequence.SelectedStep> steps
    ) {
        StringBuilder input = new StringBuilder(sequence.id().toString());
        for (PreparedSequence.SelectedStep step : steps) {
            if (step instanceof PreparedSequence.SelectedWaitStep(int ticks, int stepIndex)) {
                input.append("|wait:").append(ticks).append(':').append(stepIndex);
            } else {
                PreparedSequence.SelectedEmoteStep emoteStep = (PreparedSequence.SelectedEmoteStep) step;
                input.append('|')
                    .append(emoteStep.animation().source().sha256())
                    .append(':').append(emoteStep.loopDelayAfter())
                    .append(':').append(emoteStep.transitionTicks())
                    .append(':').append(emoteStep.stepIndex())
                    .append(':').append(emoteStep.repeatIndex());
            }
        }
        return Sha256.hashHex(input.toString().getBytes(StandardCharsets.UTF_8));
    }
}
