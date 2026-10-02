package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SequenceNodeLayout {
    private SequenceNodeLayout() {
    }

    static PreparedAnimation validateAndCreateLayout(List<PreparedSequence.Step> steps) {
        PreparedAnimation first = steps.stream()
            .filter(PreparedSequence.EmoteStep.class::isInstance)
            .map(PreparedSequence.EmoteStep.class::cast)
            .flatMap(step -> step.candidates().stream())
            .filter(PreparedSequence.AnimationChoice.class::isInstance)
            .map(PreparedSequence.AnimationChoice.class::cast)
            .map(PreparedSequence.AnimationChoice::animation)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Sequence must reference at least one animation"));
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        Map<String, PreparedDisplayData> preparedDisplayData = new LinkedHashMap<>();
        for (PreparedSequence.Step step : steps) {
            if (!(step instanceof PreparedSequence.EmoteStep emoteStep)) {
                continue;
            }
            for (PreparedSequence.Choice choice : emoteStep.candidates()) {
                if (!(choice instanceof PreparedSequence.AnimationChoice animationChoice)) {
                    continue;
                }
                PreparedAnimation animation = animationChoice.animation();
                if (!first.skinBindings().equals(animation.skinBindings())) {
                    throw new IllegalArgumentException(
                        "Sequence animations must use the same skin layout: " + first.id() + " and " + animation.id()
                    );
                }
                mergeNodes(first, animation, nodes, preparedDisplayData);
            }
        }

        EmoteAnimation layoutAnimation = new EmoteAnimation(
            first.animation().id(),
            first.animation().metadata(),
            first.animation().settings(),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(1, Map.of(), EmoteAnimation.Events.empty()), List.of());
        LoadedAnimation loaded = new LoadedAnimation(
            first.sourcePath(),
            first.source().sha256(),
            layoutAnimation,
            preparedDisplayData
        );
        return PreparedAnimation.from(loaded, first.skinBindings());
    }

    private static void mergeNodes(
        PreparedAnimation first,
        PreparedAnimation animation,
        Map<String, EmoteAnimation.Node> nodes,
        Map<String, PreparedDisplayData> preparedDisplayData
    ) {
        animation.animation().nodes().forEach((nodeId, node) -> {
            EmoteAnimation.Node existing = nodes.putIfAbsent(nodeId, node);
            if (existing != null && !compatibleNode(existing, node)) {
                throw new IllegalArgumentException(
                    "Sequence animations must use compatible nodes: " + first.id() + " and " + animation.id()
                );
            }
            PreparedDisplayData prepared = animation.source().preparedDisplayData().get(nodeId);
            if (prepared != null) {
                preparedDisplayData.putIfAbsent(nodeId, prepared);
            }
        });
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

}
