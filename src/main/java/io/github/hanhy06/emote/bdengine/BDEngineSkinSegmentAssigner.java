package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class BDEngineSkinSegmentAssigner {
    private static final double ANCHOR_DISTANCE_EPSILON = 1.0E-9D;

    List<EmoteSkinPart> assign(List<RawSkinPart> rawSkinParts) {
        if (rawSkinParts.isEmpty()) {
            return List.of();
        }

        Map<PlayerSkinPart, List<RawSkinPart>> rawSkinPartMap = new EnumMap<>(PlayerSkinPart.class);
        for (RawSkinPart rawSkinPart : rawSkinParts) {
            rawSkinPartMap.computeIfAbsent(rawSkinPart.skinPart(), ignored -> new ArrayList<>()).add(rawSkinPart);
        }

        double[] limbRoot = averageAnchor(rawSkinPartMap.get(PlayerSkinPart.BODY));
        if (limbRoot == null) {
            limbRoot = averageAnchor(rawSkinPartMap.get(PlayerSkinPart.HEAD));
        }

        List<EmoteSkinPart> skinParts = new ArrayList<>();
        for (Map.Entry<PlayerSkinPart, List<RawSkinPart>> entry : rawSkinPartMap.entrySet()) {
            PlayerSkinPart skinPart = entry.getKey();
            List<RawSkinPart> partsForSkin = orderParts(skinPart, entry.getValue(), limbRoot);
            skinParts.addAll(createSkinParts(skinPart, partsForSkin));
        }

        skinParts.sort(Comparator.comparingInt(EmoteSkinPart::partIndex));
        return List.copyOf(skinParts);
    }

    private List<RawSkinPart> orderParts(PlayerSkinPart skinPart, List<RawSkinPart> rawParts, double[] limbRoot) {
        List<RawSkinPart> parts = new ArrayList<>(rawParts);
        boolean hasExplicitOrder = isLimb(skinPart)
            && parts.stream().allMatch(part -> part.explicitOrder() != null);
        if (hasExplicitOrder) {
            parts.sort(
                Comparator.comparingInt(RawSkinPart::explicitOrder)
                    .thenComparingInt(RawSkinPart::partIndex)
            );
            return parts;
        }
        if (isLimb(skinPart) && limbRoot != null) {
            return orderConnectedParts(parts, limbRoot);
        }

        parts.sort(
            Comparator.comparingInt(RawSkinPart::partIndex)
                .thenComparing(Comparator.comparingDouble(RawSkinPart::localY).reversed())
        );
        return parts;
    }

    private boolean isLimb(PlayerSkinPart skinPart) {
        return skinPart == PlayerSkinPart.LEFT_ARM
            || skinPart == PlayerSkinPart.RIGHT_ARM
            || skinPart == PlayerSkinPart.LEFT_LEG
            || skinPart == PlayerSkinPart.RIGHT_LEG;
    }

    private double[] averageAnchor(List<RawSkinPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }

        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (RawSkinPart part : parts) {
            x += part.anchorX();
            y += part.anchorY();
            z += part.anchorZ();
        }
        return new double[]{x / parts.size(), y / parts.size(), z / parts.size()};
    }

    private List<RawSkinPart> orderConnectedParts(List<RawSkinPart> parts, double[] limbRoot) {
        List<RawSkinPart> remainingParts = new ArrayList<>(parts);
        List<RawSkinPart> orderedParts = new ArrayList<>(parts.size());
        double[] previousAnchor = limbRoot;
        while (!remainingParts.isEmpty()) {
            RawSkinPart nextPart = remainingParts.getFirst();
            double nextDistance = anchorDistanceSquared(nextPart, previousAnchor);
            for (RawSkinPart candidate : remainingParts.subList(1, remainingParts.size())) {
                double candidateDistance = anchorDistanceSquared(candidate, previousAnchor);
                if (candidateDistance < nextDistance - ANCHOR_DISTANCE_EPSILON
                    || (Math.abs(candidateDistance - nextDistance) <= ANCHOR_DISTANCE_EPSILON
                    && candidate.partIndex() < nextPart.partIndex())) {
                    nextPart = candidate;
                    nextDistance = candidateDistance;
                }
            }
            orderedParts.add(nextPart);
            remainingParts.remove(nextPart);
            previousAnchor = new double[]{nextPart.anchorX(), nextPart.anchorY(), nextPart.anchorZ()};
        }
        return orderedParts;
    }

    private double anchorDistanceSquared(RawSkinPart part, double[] anchor) {
        double offsetX = part.anchorX() - anchor[0];
        double offsetY = part.anchorY() - anchor[1];
        double offsetZ = part.anchorZ() - anchor[2];
        return offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
    }

    private List<EmoteSkinPart> createSkinParts(PlayerSkinPart skinPart, List<RawSkinPart> partsForSkin) {
        if (partsForSkin.isEmpty()) {
            return List.of();
        }
        if (skinPart == PlayerSkinPart.HEAD || partsForSkin.size() == 1) {
            return createFullSkinParts(partsForSkin);
        }
        if (partsForSkin.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            Emote.LOGGER.warn("Too many vertical skin segments for {}: {}", skinPart.id(), partsForSkin.size());
            return createFullSkinParts(partsForSkin);
        }

        double totalScale = partsForSkin.stream()
            .mapToDouble(rawSkinPart -> Math.max(rawSkinPart.localYScale(), 0.0D))
            .sum();
        if (totalScale <= 0.0D) {
            totalScale = partsForSkin.size();
        }

        List<EmoteSkinPart> segmentedSkinParts = new ArrayList<>(partsForSkin.size());
        int segmentStart = 0;
        double accumulatedScale = 0.0D;
        for (int index = 0; index < partsForSkin.size(); index++) {
            RawSkinPart rawSkinPart = partsForSkin.get(index);
            double partScale = Math.max(rawSkinPart.localYScale(), 0.0D);
            if (partScale <= 0.0D) {
                partScale = 1.0D;
            }

            accumulatedScale += partScale;
            int remainingPartCount = partsForSkin.size() - index - 1;
            int segmentEnd = calculateSegmentEnd(segmentStart, accumulatedScale, totalScale, remainingPartCount);
            segmentedSkinParts.add(new EmoteSkinPart(
                rawSkinPart.partIndex(),
                rawSkinPart.skinPart(),
                new PlayerSkinSegment(segmentStart, segmentEnd)
            ));
            segmentStart = segmentEnd;
        }
        return segmentedSkinParts;
    }

    private List<EmoteSkinPart> createFullSkinParts(List<RawSkinPart> rawSkinParts) {
        return rawSkinParts.stream()
            .map(rawSkinPart -> new EmoteSkinPart(
                rawSkinPart.partIndex(),
                rawSkinPart.skinPart(),
                PlayerSkinSegment.FULL
            ))
            .toList();
    }

    private int calculateSegmentEnd(int segmentStart, double accumulatedScale, double totalScale, int remainingPartCount) {
        int minimumEnd = segmentStart + 1;
        int maximumEnd = Math.max(minimumEnd, PlayerSkinSegment.SIDE_FACE_HEIGHT - remainingPartCount);
        int suggestedEnd = (int) Math.round(accumulatedScale * PlayerSkinSegment.SIDE_FACE_HEIGHT / totalScale);
        return Math.clamp(suggestedEnd, minimumEnd, maximumEnd);
    }

    record RawSkinPart(
        int partIndex,
        PlayerSkinPart skinPart,
        double anchorX,
        double anchorY,
        double anchorZ,
        double localY,
        double localYScale,
        Integer explicitOrder
    ) {
    }
}
