package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class SkinBindingCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkinBindingCompiler.class);

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
        if (skinPart == PlayerSkinPart.HEAD || parts.size() == 1) {
            return parts.stream()
                .map(part -> new SkinBinding(
                    part.nodeId(),
                    participantSkinPart.participant(),
                    new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)
                ))
                .toList();
        }
        if (parts.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            LOGGER.warn(
                "Too many vertical JSON skin segments for {} {}: {}",
                participantSkinPart.participant(),
                skinPart.id(),
                parts.size()
            );
            return parts.stream()
                .map(part -> new SkinBinding(
                    part.nodeId(),
                    participantSkinPart.participant(),
                    new PlayerSkinRegion(skinPart, PlayerSkinSegment.FULL)
                ))
                .toList();
        }

        double totalScale = parts.stream().mapToDouble(RawPart::localYScale).sum();
        if (totalScale <= 0.0D) {
            totalScale = parts.size();
        }
        List<SkinBinding> result = new ArrayList<>();
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
            result.add(new SkinBinding(
                part.nodeId(),
                participantSkinPart.participant(),
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

    private record RawPart(String nodeId, int order, double localYScale) {
    }

    private record ParticipantSkinPart(ParticipantRole participant, PlayerSkinPart skinPart) {
    }
}
