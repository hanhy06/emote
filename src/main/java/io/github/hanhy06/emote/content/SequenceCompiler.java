package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.util.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class SequenceCompiler {
    private SequenceCompiler() {
    }

    static PreparedAnimation compile(
        EmoteSequence sequence,
        List<PreparedSequence.SelectedStep> steps,
        PreparedAnimation layoutAnchor
    ) {
        List<PreparedAnimation.PlaybackSegment> playbackSegments = new ArrayList<>();
        Map<Integer, Set<String>> hiddenNodes = new HashMap<>();
        if (steps.isEmpty() || !(steps.getFirst() instanceof PreparedSequence.SelectedEmoteStep)) {
            hiddenNodes.put(0, nodesToHide(layoutAnchor.animation(), null));
        }
        long offset = 0L;
        boolean hasPreviousPose = false;
        for (PreparedSequence.SelectedStep selectedStep : steps) {
            if (selectedStep instanceof PreparedSequence.SelectedWaitStep(int ticks)) {
                offset += ticks;
                continue;
            }
            PreparedSequence.SelectedEmoteStep step = (PreparedSequence.SelectedEmoteStep) selectedStep;
            EmoteAnimation animation = step.animation().animation();
            int transitionStartTick = requireTick(offset, sequence);
            int transitionTicks = hasPreviousPose ? step.transitionTicks() : 0;
            int segmentOffset = requireTick(offset + transitionTicks, sequence);
            playbackSegments.add(new PreparedAnimation.PlaybackSegment(
                transitionStartTick,
                segmentOffset,
                requireTick(offset + transitionTicks + animation.timeline().durationTicks(), sequence),
                step.animation()
            ));
            hiddenNodes.put(segmentOffset, nodesToHide(layoutAnchor.animation(), animation));

            offset += transitionTicks + animation.timeline().durationTicks();
            if (step.loopDelayAfter() && animation.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP) {
                offset += animation.settings().playback().loopDelayTicks();
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
                new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0, 0, 0)
            ),
            EmoteAnimation.MolangPrograms.empty(),
            layoutAnchor.animation().nodes(),
            new EmoteAnimation.Timeline(
                Math.max(requireTick(offset, sequence), 1),
                Map.of(),
                EmoteAnimation.Events.empty()
            ), sequence.callbacks());
        LoadedAnimation loaded = new LoadedAnimation(sequence.sourcePath(), fingerprint(sequence, steps),
            compiledAnimation, layoutAnchor.source().preparedDisplayData());
        PreparedAnimation preparedLayout = PreparedAnimation.from(loaded, layoutAnchor.skinBindings());
        return PreparedAnimation.sequence(preparedLayout, playbackSegments, hiddenNodes);
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
            if (step instanceof PreparedSequence.SelectedWaitStep(int ticks)) {
                input.append("|wait:").append(ticks);
            } else {
                PreparedSequence.SelectedEmoteStep emoteStep = (PreparedSequence.SelectedEmoteStep) step;
                input.append('|')
                    .append(emoteStep.animation().source().sha256())
                    .append(':').append(emoteStep.loopDelayAfter())
                    .append(':').append(emoteStep.transitionTicks());
            }
        }
        return Sha256.hashHex(input.toString().getBytes(StandardCharsets.UTF_8));
    }
}
