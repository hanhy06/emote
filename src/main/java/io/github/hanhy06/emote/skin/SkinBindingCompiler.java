package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;

import java.util.*;
import java.util.stream.Collectors;

public final class SkinBindingCompiler {
    public List<SkinBinding> compile(EmoteAnimation animation) {
        Map<ParticipantSkinPart, List<RawPart>> byPart = new HashMap<>();
        for (Map.Entry<String, EmoteAnimation.Node> entry : animation.nodes().entrySet()) {
            if (!(entry.getValue() instanceof EmoteAnimation.ItemNode itemNode) || itemNode.skin() == null) {
                continue;
            }
            PlayerSkinPart skinPart = convert(itemNode.skin().part());
            ParticipantSkinPart participantSkinPart = new ParticipantSkinPart(itemNode.skin().participant(), skinPart);
            byPart.computeIfAbsent(participantSkinPart, ignored -> new ArrayList<>()).add(new RawPart(
                entry.getKey(),
                itemNode.skin().order(),
                Math.abs(itemNode.transform().scale().y())
            ));
        }

        List<SkinBinding> result = new ArrayList<>();
        for (Map.Entry<ParticipantSkinPart, List<RawPart>> entry : byPart.entrySet()) {
            List<RawPart> parts = entry.getValue().stream()
                .sorted(Comparator.comparingInt(RawPart::order).thenComparing(RawPart::nodeId))
                .toList();
            result.addAll(createParts(entry.getKey(), parts));
        }
        result.sort(Comparator.comparing(SkinBinding::participant).thenComparing(SkinBinding::nodeId));
        return List.copyOf(result);
    }

    private List<SkinBinding> createParts(ParticipantSkinPart participantSkinPart, List<RawPart> parts) {
        PlayerSkinPart skinPart = participantSkinPart.skinPart();
        List<OrderGroup> orderGroups = parts.stream()
            .collect(Collectors.groupingBy(RawPart::order, LinkedHashMap::new, Collectors.toList()))
            .values().stream()
            .map(OrderGroup::new)
            .toList();
        if (skinPart == PlayerSkinPart.HEAD || orderGroups.size() == 1) {
            return parts.stream()
                .map(part -> new SkinBinding(
                    part.nodeId(),
                    participantSkinPart.participant(),
                    new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)
                ))
                .toList();
        }
        if (orderGroups.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            EmoteMod.LOGGER.warn(
                "Too many vertical JSON skin segments for {} {}: {}",
                participantSkinPart.participant(),
                skinPart.id(),
                orderGroups.size()
            );
            return parts.stream()
                .map(part -> new SkinBinding(
                    part.nodeId(),
                    participantSkinPart.participant(),
                    new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)
                ))
                .toList();
        }

        double totalScale = orderGroups.stream().mapToDouble(OrderGroup::localYScale).sum();
        if (totalScale <= 0.0D) {
            totalScale = orderGroups.size();
        }
        List<SkinBinding> result = new ArrayList<>();
        int segmentStart = 0;
        double accumulatedScale = 0.0D;
        for (int index = 0; index < orderGroups.size(); index++) {
            OrderGroup group = orderGroups.get(index);
            accumulatedScale += group.localYScale() > 0.0D ? group.localYScale() : 1.0D;
            int remaining = orderGroups.size() - index - 1;
            int minimumEnd = segmentStart + 1;
            int maximumEnd = Math.max(minimumEnd, PlayerSkinSegment.SIDE_FACE_HEIGHT - remaining);
            int suggestedEnd = (int) Math.round(accumulatedScale * PlayerSkinSegment.SIDE_FACE_HEIGHT / totalScale);
            int segmentEnd = Math.clamp(suggestedEnd, minimumEnd, maximumEnd);
            PlayerSkinRegion region = new PlayerSkinRegion(skinPart, new PlayerSkinSegment(segmentStart, segmentEnd));
            for (RawPart part : group.parts()) {
                result.add(new SkinBinding(part.nodeId(), participantSkinPart.participant(), region));
            }
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

    private record RawPart(String nodeId, int order, double localYScale) {
    }

    private record OrderGroup(List<RawPart> parts, double localYScale) {
        private OrderGroup(List<RawPart> parts) {
            this(parts, parts.stream()
                .mapToDouble(RawPart::localYScale)
                .filter(scale -> scale > 0.0D)
                .min()
                .orElse(0.0D));
        }
    }

    private record ParticipantSkinPart(ParticipantRole participant, PlayerSkinPart skinPart) {
    }
}
