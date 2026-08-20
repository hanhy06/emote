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
        List<EmoteAnimation.Keyframe> keyframes = new ArrayList<>();
        List<EmoteAnimation.TimelineEvent> timelineEvents = new ArrayList<>();
        if (steps.isEmpty() || !(steps.getFirst() instanceof PreparedSequence.SelectedEmoteStep)) {
            keyframes.add(createHiddenKeyframe(layoutAnchor.animation(), 0));
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
            keyframes.add(createResetKeyframe(layoutAnchor.animation(), animation, segmentOffset));
            for (EmoteAnimation.Keyframe keyframe : animation.timeline().keyframes()) {
                keyframes.add(new EmoteAnimation.Keyframe(
                    requireTick(offset + keyframe.tick(), sequence),
                    keyframe.nodeTransforms(),
                    keyframe.nodeStates()
                ));
            }
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
            layoutAnchor.animation().nodes(),
            new EmoteAnimation.Timeline(
                Math.max(requireTick(offset, sequence), 1),
                keyframes,
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
        return layout.generatedPartner()
            ? PreparedEmote.from(loaded)
            : PreparedEmote.from(loaded, layoutAnchor.skinParts());
    }

    private static EmoteAnimation.Keyframe createHiddenKeyframe(EmoteAnimation animation, int tick) {
        Map<String, EmoteAnimation.NodeState> states = new LinkedHashMap<>();
        animation.nodes().keySet().forEach(nodeId -> states.put(nodeId, new EmoteAnimation.NodeState(false)));
        return new EmoteAnimation.Keyframe(tick, Map.of(), states);
    }

    private static EmoteAnimation.Keyframe createResetKeyframe(
        EmoteAnimation layout,
        EmoteAnimation animation,
        int tick
    ) {
        Map<String, EmoteAnimation.NodeTransform> transforms = new LinkedHashMap<>();
        Map<String, EmoteAnimation.NodeState> states = new LinkedHashMap<>();
        layout.nodes().keySet().forEach(nodeId -> states.put(nodeId, new EmoteAnimation.NodeState(false)));
        animation.nodes().forEach((nodeId, node) -> {
            transforms.put(nodeId, new EmoteAnimation.NodeTransform(node.defaultMatrix(), 0));
            states.put(nodeId, new EmoteAnimation.NodeState(node.visible()));
        });
        return new EmoteAnimation.Keyframe(tick, transforms, states);
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
