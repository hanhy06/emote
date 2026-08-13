package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.playback.PlaybackPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class SequenceAnimationCompiler {
    private SequenceAnimationCompiler() {
    }

    static RegisteredEmote compile(EmoteSequence sequence, List<RegisteredSequence.SelectedStep> steps) {
        RegisteredEmote first = steps.stream()
            .filter(RegisteredSequence.SelectedEmoteStep.class::isInstance)
            .map(RegisteredSequence.SelectedEmoteStep.class::cast)
            .map(RegisteredSequence.SelectedEmoteStep::animation)
            .findFirst()
            .orElseThrow();

        List<EmoteAnimation.Keyframe> keyframes = new ArrayList<>();
        List<EmoteAnimation.TimelineEvent> timelineEvents = new ArrayList<>();
        long offset = 0L;
        for (RegisteredSequence.SelectedStep selectedStep : steps) {
            if (selectedStep instanceof RegisteredSequence.SelectedWaitStep(int ticks)) {
                offset += ticks;
                continue;
            }
            RegisteredSequence.SelectedEmoteStep step = (RegisteredSequence.SelectedEmoteStep) selectedStep;
            EmoteAnimation animation = step.animation().animation();
            int segmentOffset = requireTick(offset, sequence);
            keyframes.add(createResetKeyframe(animation, segmentOffset));
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
            first.animation().nodes(),
            new EmoteAnimation.Timeline(
                requireTick(offset, sequence),
                keyframes,
                new EmoteAnimation.Events(List.of(), timelineEvents, List.of(), List.of())
            )
        );
        boolean automaticPartner = sequence.participants() != null
            && compiledAnimation.nodes().values().stream().noneMatch(node -> node.space() == EmoteAnimation.NodeSpace.PARTNER);
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData = first.source().preparedDisplayData();
        if (automaticPartner) {
            AutomaticPartnerAnimation expanded = addAutomaticPartner(compiledAnimation, preparedDisplayData);
            compiledAnimation = expanded.animation();
            preparedDisplayData = expanded.preparedDisplayData();
        }
        EmoteAnimation.Loaded loaded = new EmoteAnimation.Loaded(
            sequence.sourcePath(),
            fingerprint(sequence, steps),
            compiledAnimation,
            preparedDisplayData
        );
        return automaticPartner
            ? RegisteredEmote.from(loaded)
            : new RegisteredEmote(loaded, first.skinParts(), PlaybackPlan.compile(compiledAnimation));
    }

    private static AutomaticPartnerAnimation addAutomaticPartner(
        EmoteAnimation animation,
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData
    ) {
        Map<String, String> partnerIds = partnerNodeIds(animation.nodes());
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>(animation.nodes());
        Map<String, EmoteAnimation.PreparedDisplayData> expandedPreparedData = new LinkedHashMap<>(preparedDisplayData);
        partnerIds.forEach((sourceId, partnerId) -> {
            nodes.put(partnerId, asPartnerNode(animation.nodes().get(sourceId)));
            EmoteAnimation.PreparedDisplayData prepared = preparedDisplayData.get(sourceId);
            if (prepared != null) {
                expandedPreparedData.put(partnerId, prepared);
            }
        });

        List<EmoteAnimation.Keyframe> keyframes = animation.timeline().keyframes().stream()
            .map(keyframe -> duplicatePartnerTracks(keyframe, partnerIds))
            .toList();
        EmoteAnimation expanded = new EmoteAnimation(
            animation.id(),
            animation.metadata(),
            animation.settings(),
            nodes,
            new EmoteAnimation.Timeline(
                animation.timeline().durationTicks(),
                keyframes,
                animation.timeline().events()
            )
        );
        return new AutomaticPartnerAnimation(expanded, expandedPreparedData);
    }

    private static Map<String, String> partnerNodeIds(Map<String, EmoteAnimation.Node> nodes) {
        List<String> initiatorIds = nodes.entrySet().stream()
            .filter(entry -> entry.getValue().space() == EmoteAnimation.NodeSpace.INITIATOR)
            .map(Map.Entry::getKey)
            .toList();
        String prefix = "__partner__";
        while (initiatorIds.stream().map(prefix::concat).anyMatch(nodes::containsKey)) {
            prefix += "_";
        }
        Map<String, String> ids = new LinkedHashMap<>();
        for (String initiatorId : initiatorIds) {
            ids.put(initiatorId, prefix + initiatorId);
        }
        return ids;
    }

    private static EmoteAnimation.Node asPartnerNode(EmoteAnimation.Node node) {
        return switch (node) {
            case EmoteAnimation.ItemNode item -> new EmoteAnimation.ItemNode(
                item.visible(),
                EmoteAnimation.NodeSpace.PARTNER,
                item.defaultMatrix(),
                item.entityNbt(),
                item.itemStackNbt(),
                item.itemDisplay(),
                item.skin() == null ? null : new EmoteAnimation.Skin(
                    ParticipantRole.PARTNER,
                    item.skin().part(),
                    item.skin().order()
                )
            );
            case EmoteAnimation.BlockNode block -> new EmoteAnimation.BlockNode(
                block.visible(), EmoteAnimation.NodeSpace.PARTNER, block.defaultMatrix(), block.entityNbt(), block.blockStateNbt()
            );
            case EmoteAnimation.TextNode text -> new EmoteAnimation.TextNode(
                text.visible(), EmoteAnimation.NodeSpace.PARTNER, text.defaultMatrix(), text.entityNbt(), text.text()
            );
            case EmoteAnimation.AnchorNode anchor -> new EmoteAnimation.AnchorNode(
                EmoteAnimation.NodeSpace.PARTNER, anchor.defaultMatrix()
            );
        };
    }

    private static EmoteAnimation.Keyframe duplicatePartnerTracks(
        EmoteAnimation.Keyframe keyframe,
        Map<String, String> partnerIds
    ) {
        Map<String, EmoteAnimation.NodeTransform> transforms = new LinkedHashMap<>(keyframe.nodeTransforms());
        Map<String, EmoteAnimation.NodeState> states = new LinkedHashMap<>(keyframe.nodeStates());
        partnerIds.forEach((sourceId, partnerId) -> {
            EmoteAnimation.NodeTransform transform = keyframe.nodeTransforms().get(sourceId);
            if (transform != null) {
                transforms.put(partnerId, transform);
            }
            EmoteAnimation.NodeState state = keyframe.nodeStates().get(sourceId);
            if (state != null) {
                states.put(partnerId, state);
            }
        });
        return new EmoteAnimation.Keyframe(keyframe.tick(), transforms, states);
    }

    private record AutomaticPartnerAnimation(
        EmoteAnimation animation,
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData
    ) {
    }

    static void validateCompatibleAnimations(List<RegisteredSequence.Step> steps) {
        RegisteredEmote first = steps.stream()
            .filter(RegisteredSequence.EmoteStep.class::isInstance)
            .map(RegisteredSequence.EmoteStep.class::cast)
            .map(step -> step.candidates().getFirst().animation())
            .findFirst()
            .orElseThrow();
        for (RegisteredSequence.Step step : steps) {
            if (!(step instanceof RegisteredSequence.EmoteStep emoteStep)) {
                continue;
            }
            for (RegisteredSequence.Choice choice : emoteStep.candidates()) {
                RegisteredEmote animation = choice.animation();
                if (!compatibleNodes(first.animation().nodes(), animation.animation().nodes())) {
                    throw new IllegalArgumentException(
                        "Sequence animations must use compatible nodes: " + first.id() + " and " + animation.id()
                    );
                }
                if (!first.skinParts().equals(animation.skinParts())) {
                    throw new IllegalArgumentException(
                        "Sequence animations must use the same skin layout: " + first.id() + " and " + animation.id()
                    );
                }
                EmoteAnimation.Events events = animation.animation().timeline().events();
                if (!events.start().isEmpty() || !events.loop().isEmpty() || !events.stop().isEmpty()) {
                    throw new IllegalArgumentException(
                        "Sequence animation lifecycle events are not supported by compiled sequences: " + animation.id()
                    );
                }
            }
        }
    }

    private static boolean compatibleNodes(
        Map<String, EmoteAnimation.Node> first,
        Map<String, EmoteAnimation.Node> candidate
    ) {
        if (!first.keySet().equals(candidate.keySet())) {
            return false;
        }
        for (String nodeId : first.keySet()) {
            if (!compatibleNode(first.get(nodeId), candidate.get(nodeId))) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatibleNode(EmoteAnimation.Node first, EmoteAnimation.Node candidate) {
        if (first.space() != candidate.space()) {
            return false;
        }
        return switch (first) {
            case EmoteAnimation.ItemNode item -> candidate instanceof EmoteAnimation.ItemNode other
                && item.entityNbt().equals(other.entityNbt())
                && item.itemStackNbt().equals(other.itemStackNbt())
                && item.itemDisplay().equals(other.itemDisplay())
                && java.util.Objects.equals(item.skin(), other.skin());
            case EmoteAnimation.BlockNode block -> candidate instanceof EmoteAnimation.BlockNode other
                && block.entityNbt().equals(other.entityNbt())
                && block.blockStateNbt().equals(other.blockStateNbt());
            case EmoteAnimation.TextNode text -> candidate instanceof EmoteAnimation.TextNode other
                && text.entityNbt().equals(other.entityNbt())
                && text.text().equals(other.text());
            case EmoteAnimation.AnchorNode ignored -> candidate instanceof EmoteAnimation.AnchorNode;
        };
    }

    private static EmoteAnimation.Keyframe createResetKeyframe(EmoteAnimation animation, int tick) {
        Map<String, EmoteAnimation.NodeTransform> transforms = new LinkedHashMap<>();
        Map<String, EmoteAnimation.NodeState> states = new LinkedHashMap<>();
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

    private static String fingerprint(EmoteSequence sequence, List<RegisteredSequence.SelectedStep> steps) {
        StringBuilder input = new StringBuilder(sequence.id().toString());
        for (RegisteredSequence.SelectedStep step : steps) {
            if (step instanceof RegisteredSequence.SelectedWaitStep(int ticks)) {
                input.append("|wait:").append(ticks);
            } else {
                RegisteredSequence.SelectedEmoteStep emoteStep = (RegisteredSequence.SelectedEmoteStep) step;
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
