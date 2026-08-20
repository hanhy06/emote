package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class SequenceCompiler {
    private SequenceCompiler() {
    }

    static PreparedEmote compile(
        EmoteSequence sequence,
        List<PreparedSequence.SelectedStep> steps,
        PreparedEmote layoutAnchor
    ) {
        List<EmoteAnimation.TimelineEvent> timelineEvents = new ArrayList<>();
        List<PreparedEmote.PlaybackSegment> playbackSegments = new ArrayList<>();
        Map<Integer, Map<String, Boolean>> visibility = new HashMap<>();
        if (steps.isEmpty() || !(steps.getFirst() instanceof PreparedSequence.SelectedEmoteStep)) {
            visibility.put(0, hiddenNodes(layoutAnchor.animation(), null));
        }
        long offset = 0L;
        for (PreparedSequence.SelectedStep selectedStep : steps) {
            if (selectedStep instanceof PreparedSequence.SelectedWaitStep(int ticks)) {
                offset += ticks;
                continue;
            }
            PreparedSequence.SelectedEmoteStep step = (PreparedSequence.SelectedEmoteStep) selectedStep;
            EmoteAnimation animation = step.animation().animation();
            int segmentOffset = requireTick(offset, sequence);
            playbackSegments.add(new PreparedEmote.PlaybackSegment(
                segmentOffset,
                requireTick(offset + animation.timeline().durationTicks(), sequence),
                step.animation(),
                Map.of()
            ));
            visibility.put(segmentOffset, hiddenNodes(layoutAnchor.animation(), animation));
            for (EmoteAnimation.TimelineEvent event : animation.timeline().events().timeline()) {
                timelineEvents.add(new EmoteAnimation.TimelineEvent(
                    requireTick(offset + event.tick(), sequence),
                    event.source(),
                    event.origin(),
                    event.commands()
                ));
            }

            offset += animation.timeline().durationTicks();
            if (step.loopDelayAfter() && animation.settings().playback().mode() == EmoteAnimation.LoopMode.LOOP) {
                offset += animation.settings().playback().loopDelayTicks();
            }
        }

        EmoteAnimation compiledAnimation = new EmoteAnimation(
            sequence.id(),
            sequence.metadata(),
            new EmoteAnimation.Settings(
                true,
                sequence.settings().cooldownTicks(),
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
        SequenceNodeLayout.Expansion layout = SequenceNodeLayout.expandCollaborativeLayout(
            sequence.participants() != null,
            compiledAnimation,
            layoutAnchor.source().preparedDisplayData()
        );
        compiledAnimation = layout.animation();
        LoadedAnimation loaded = new LoadedAnimation(
            sequence.sourcePath(),
            fingerprint(sequence, steps),
            compiledAnimation,
            layout.preparedDisplayData()
        );
        List<PreparedEmote.PlaybackSegment> expandedSegments = playbackSegments.stream()
            .map(segment -> new PreparedEmote.PlaybackSegment(
                segment.startTick(),
                segment.endTick(),
                segment.animation(),
                layout.partnerNodeIds()
            ))
            .toList();
        PreparedEmote preparedLayout = layout.generatedPartner()
            ? PreparedEmote.from(loaded)
            : PreparedEmote.from(loaded, layoutAnchor.skinParts());
        return PreparedEmote.sequence(preparedLayout, expandedSegments, expandVisibility(visibility, layout.partnerNodeIds()));
    }

    private static Map<String, Boolean> hiddenNodes(EmoteAnimation layout, EmoteAnimation active) {
        Map<String, Boolean> visibility = new LinkedHashMap<>();
        layout.nodes().keySet().forEach(nodeId -> visibility.put(nodeId, false));
        if (active != null) {
            active.nodes().keySet().forEach(visibility::remove);
        }
        return Map.copyOf(visibility);
    }

    private static Map<Integer, Map<String, Boolean>> expandVisibility(
        Map<Integer, Map<String, Boolean>> source,
        Map<String, String> partnerNodeIds
    ) {
        Map<Integer, Map<String, Boolean>> expanded = new HashMap<>();
        source.forEach((tick, values) -> {
            Map<String, Boolean> tickValues = new LinkedHashMap<>(values);
            partnerNodeIds.forEach((sourceId, partnerId) -> {
                Boolean visible = values.get(sourceId);
                if (visible != null) {
                    tickValues.put(partnerId, visible);
                }
            });
            expanded.put(tick, Map.copyOf(tickValues));
        });
        return Map.copyOf(expanded);
    }

    private static int requireTick(long tick, EmoteSequence sequence) {
        if (tick > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Compiled sequence is too long: " + sequence.id());
        }
        return (int) tick;
    }

    private static String fingerprint(EmoteSequence sequence, List<PreparedSequence.SelectedStep> steps) {
        StringBuilder input = new StringBuilder(sequence.id().toString());
        for (PreparedSequence.SelectedStep step : steps) {
            if (step instanceof PreparedSequence.SelectedWaitStep(int ticks)) {
                input.append("|wait:").append(ticks);
            } else {
                PreparedSequence.SelectedEmoteStep emoteStep = (PreparedSequence.SelectedEmoteStep) step;
                input.append('|').append(emoteStep.animation().source().sha256()).append(':').append(emoteStep.loopDelayAfter());
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
