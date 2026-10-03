package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SequenceNodeLayout {
    private SequenceNodeLayout() {
    }

    static PreparedEmote validateAndCreateLayout(List<PreparedSequence.Step> steps) {
        PreparedEmote first = steps.stream()
            .filter(PreparedSequence.AnimationStep.class::isInstance)
            .map(PreparedSequence.AnimationStep.class::cast)
            .flatMap(step -> step.candidates().stream())
            .filter(PreparedSequence.AnimationChoice.class::isInstance)
            .map(PreparedSequence.AnimationChoice.class::cast)
            .map(PreparedSequence.AnimationChoice::animation)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Sequence must reference at least one animation"));
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        Map<String, PreparedDisplayData> preparedDisplayData = new LinkedHashMap<>();
        for (PreparedSequence.Step step : steps) {
            if (!(step instanceof PreparedSequence.AnimationStep emoteStep)) {
                continue;
            }
            for (PreparedSequence.Choice choice : emoteStep.candidates()) {
                if (!(choice instanceof PreparedSequence.AnimationChoice animationChoice)) {
                    continue;
                }
                PreparedEmote animation = animationChoice.animation();
                if (!first.skinBindings().equals(animation.skinBindings())) {
                    throw new IllegalArgumentException(
                        "Sequence animations must use the same skin layout: " + first.id() + " and " + animation.id()
                    );
                }
                mergeNodes(first, animation, nodes, preparedDisplayData);
            }
        }

        EmoteAnimation layoutModel = new EmoteAnimation(
            first.model().id(),
            first.model().metadata(),
            first.model().settings(),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(1, Map.of(), EmoteAnimation.Events.empty()), List.of());
        LoadedAnimation loaded = new LoadedAnimation(
            first.sourcePath(),
            first.source().sha256(),
            layoutModel,
            preparedDisplayData
        );
        return PreparedEmote.from(loaded, first.skinBindings());
    }

    private static void mergeNodes(
        PreparedEmote first,
        PreparedEmote animation,
        Map<String, EmoteAnimation.Node> nodes,
        Map<String, PreparedDisplayData> preparedDisplayData
    ) {
        animation.model().nodes().forEach((nodeId, node) -> {
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
