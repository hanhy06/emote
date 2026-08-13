package io.github.hanhy06.emote.skin.animation;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;

import java.util.*;

public final class AnimationSkinBindingFactory {
    public List<AnimationSkinBinding> create(EmoteAnimation animation) {
        Map<PlayerSkinPart, List<RawPart>> byPart = new EnumMap<>(PlayerSkinPart.class);
        for (Map.Entry<String, EmoteAnimation.Node> entry : animation.nodes().entrySet()) {
            if (!(entry.getValue() instanceof EmoteAnimation.ItemNode itemNode) || itemNode.skin() == null) {
                continue;
            }
            PlayerSkinPart skinPart = convert(itemNode.skin().part());
            byPart.computeIfAbsent(skinPart, ignored -> new ArrayList<>()).add(new RawPart(
                entry.getKey(),
                itemNode.skin().order(),
                localYScale(itemNode.defaultMatrix())
            ));
        }

        List<AnimationSkinBinding> result = new ArrayList<>();
        for (Map.Entry<PlayerSkinPart, List<RawPart>> entry : byPart.entrySet()) {
            List<RawPart> parts = entry.getValue().stream()
                .sorted(Comparator.comparingInt(RawPart::order).thenComparing(RawPart::nodeId))
                .toList();
            result.addAll(createParts(entry.getKey(), parts));
        }
        result.sort(Comparator.comparing(AnimationSkinBinding::nodeId));
        return List.copyOf(result);
    }

    private List<AnimationSkinBinding> createParts(PlayerSkinPart skinPart, List<RawPart> parts) {
        if (skinPart == PlayerSkinPart.HEAD || parts.size() == 1) {
            return parts.stream()
                .map(part -> new AnimationSkinBinding(part.nodeId(), new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)))
                .toList();
        }
        if (parts.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            Emote.LOGGER.warn("Too many vertical JSON skin segments for {}: {}", skinPart.id(), parts.size());
            return parts.stream()
                .map(part -> new AnimationSkinBinding(part.nodeId(), new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)))
                .toList();
        }

        double totalScale = parts.stream().mapToDouble(RawPart::localYScale).sum();
        if (totalScale <= 0.0D) {
            totalScale = parts.size();
        }
        List<AnimationSkinBinding> result = new ArrayList<>();
        int segmentStart = 0;
        double accumulatedScale = 0.0D;
        for (int index = 0; index < parts.size(); index++) {
            RawPart part = parts.get(index);
            accumulatedScale += part.localYScale() > 0.0D ? part.localYScale() : 1.0D;
            int remaining = parts.size() - index - 1;
            int minimumEnd = segmentStart + 1;
            int maximumEnd = Math.max(minimumEnd, PlayerSkinSegment.SIDE_FACE_HEIGHT - remaining);
            int suggestedEnd = (int) Math.round(accumulatedScale * PlayerSkinSegment.SIDE_FACE_HEIGHT / totalScale);
            int segmentEnd = Math.clamp(suggestedEnd, minimumEnd, maximumEnd);
            result.add(new AnimationSkinBinding(
                part.nodeId(),
                new PlayerSkinRegion(skinPart, new PlayerSkinSegment(segmentStart, segmentEnd))
            ));
            segmentStart = segmentEnd;
        }
        return result;
    }

    private PlayerSkinPart convert(EmoteAnimation.SkinPart part) {
        return switch (part) {
            case HEAD -> PlayerSkinPart.HEAD;
            case BODY -> PlayerSkinPart.BODY;
            case LEFT_ARM -> PlayerSkinPart.LEFT_ARM;
            case RIGHT_ARM -> PlayerSkinPart.RIGHT_ARM;
            case LEFT_LEG -> PlayerSkinPart.LEFT_LEG;
            case RIGHT_LEG -> PlayerSkinPart.RIGHT_LEG;
        };
    }

    private double localYScale(EmoteAnimation.Matrix matrix) {
        double x = matrix.value(1);
        double y = matrix.value(5);
        double z = matrix.value(9);
        return Math.sqrt(x * x + y * y + z * z);
    }

    private record RawPart(String nodeId, int order, double localYScale) {
    }
}
