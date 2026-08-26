package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class SequenceCompiler {
    private SequenceCompiler() {
    }

    static PreparedAnimation compile(
        EmoteSequence sequence,
        List<PreparedSequence.SelectedStep> steps,
        PreparedAnimation layoutAnchor,
        boolean initialPoseAvailable
    ) {
        List<EmoteAnimation.TimelineEvent> timelineEvents = new ArrayList<>();
        List<PreparedAnimation.PlaybackSegment> playbackSegments = new ArrayList<>();
        Map<Integer, Set<String>> hiddenNodes = new HashMap<>();
        if (steps.isEmpty() || !(steps.getFirst() instanceof PreparedSequence.SelectedEmoteStep)) {
            hiddenNodes.put(0, nodesToHide(layoutAnchor.animation(), null));
        }
        long offset = 0L;
        boolean hasPreviousPose = initialPoseAvailable;
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
                step.animation(),
                Map.of()
            ));
            hiddenNodes.put(segmentOffset, nodesToHide(layoutAnchor.animation(), animation));
            for (EmoteAnimation.TimelineEvent event : animation.timeline().events().timeline()) {
                timelineEvents.add(new EmoteAnimation.TimelineEvent(
                    requireTick((long) segmentOffset + event.tick(), sequence),
                    event.source(),
                    event.origin(),
                    event.commands(),
                    event.callbacks()
                ));
            }

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
                sequence.settings().player(),
                new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0)
            ),
            EmoteAnimation.MolangPrograms.empty(),
            layoutAnchor.animation().nodes(),
            new EmoteAnimation.Timeline(
                Math.max(requireTick(offset, sequence), 1),
                Map.of(),
                new EmoteAnimation.Events(List.of(), timelineEvents, List.of(), List.of())
            )
        );
        SequenceNodeLayout.Expansion layout = SequenceNodeLayout.expandPartnerLayout(
            sequence.participants() != null,
            compiledAnimation,
            layoutAnchor.source().preparedDisplayData()
        );
        compiledAnimation = layout.animation();
        LoadedAnimation loaded = new LoadedAnimation(
            sequence.sourcePath(),
            fingerprint(sequence, steps, initialPoseAvailable),
            compiledAnimation,
            layout.preparedDisplayData()
        );
        List<PreparedAnimation.PlaybackSegment> expandedSegments = playbackSegments.stream()
            .map(segment -> new PreparedAnimation.PlaybackSegment(
                segment.transitionStartTick(),
                segment.startTick(),
                segment.endTick(),
                segment.animation(),
                layout.partnerNodeIds()
            ))
            .toList();
        PreparedAnimation preparedLayout = layout.generatedPartner()
            ? PreparedAnimation.from(loaded)
            : PreparedAnimation.from(loaded, layoutAnchor.skinBindings());
        return PreparedAnimation.sequence(preparedLayout, expandedSegments, expandHiddenNodes(hiddenNodes, layout.partnerNodeIds()));
    }

    private static Set<String> nodesToHide(EmoteAnimation layout, EmoteAnimation active) {
        Set<String> hiddenNodes = new LinkedHashSet<>(layout.nodes().keySet());
        if (active != null) {
            hiddenNodes.removeAll(active.nodes().keySet());
        }
        return Set.copyOf(hiddenNodes);
    }

    private static Map<Integer, Set<String>> expandHiddenNodes(
        Map<Integer, Set<String>> source,
        Map<String, String> partnerNodeIds
    ) {
        Map<Integer, Set<String>> expanded = new HashMap<>();
        source.forEach((tick, nodeIds) -> {
            Set<String> tickNodeIds = new LinkedHashSet<>(nodeIds);
            partnerNodeIds.forEach((sourceId, partnerId) -> {
                if (nodeIds.contains(sourceId)) {
                    tickNodeIds.add(partnerId);
                }
            });
            expanded.put(tick, Set.copyOf(tickNodeIds));
        });
        return Map.copyOf(expanded);
    }

    private static int requireTick(long tick, EmoteSequence sequence) {
        if (tick > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Compiled sequence is too long: " + sequence.id());
        }
        return (int) tick;
    }

    private static String fingerprint(
        EmoteSequence sequence,
        List<PreparedSequence.SelectedStep> steps,
        boolean initialPoseAvailable
    ) {
        StringBuilder input = new StringBuilder(sequence.id().toString()).append('|').append(initialPoseAvailable);
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
