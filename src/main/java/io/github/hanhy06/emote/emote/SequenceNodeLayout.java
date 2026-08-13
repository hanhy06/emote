package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SequenceNodeLayout {
    private SequenceNodeLayout() {
    }

    static Expansion expandCollaborativeLayout(
        boolean collaborative,
        EmoteAnimation animation,
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData
    ) {
        if (!collaborative || animation.nodes().values().stream().anyMatch(node -> node.space() == EmoteAnimation.NodeSpace.PARTNER)) {
            return new Expansion(animation, preparedDisplayData, false);
        }

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
        return new Expansion(expanded, expandedPreparedData, true);
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
                && Objects.equals(item.skin(), other.skin());
            case EmoteAnimation.BlockNode block -> candidate instanceof EmoteAnimation.BlockNode other
                && block.entityNbt().equals(other.entityNbt())
                && block.blockStateNbt().equals(other.blockStateNbt());
            case EmoteAnimation.TextNode text -> candidate instanceof EmoteAnimation.TextNode other
                && text.entityNbt().equals(other.entityNbt())
                && text.text().equals(other.text());
            case EmoteAnimation.AnchorNode ignored -> candidate instanceof EmoteAnimation.AnchorNode;
        };
    }

    record Expansion(
        EmoteAnimation animation,
        Map<String, EmoteAnimation.PreparedDisplayData> preparedDisplayData,
        boolean generatedPartner
    ) {
        Expansion {
            Objects.requireNonNull(animation, "animation");
            preparedDisplayData = Map.copyOf(preparedDisplayData);
        }
    }
}
