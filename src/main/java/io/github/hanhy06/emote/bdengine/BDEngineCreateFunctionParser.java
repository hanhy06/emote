package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinSegment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BDEngineCreateFunctionParser {
    private static final Pattern PLAYER_SKIN_MARKER_PATTERN = Pattern.compile("name\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORDERED_SKIN_MARKER_PATTERN = Pattern.compile("^emote:([a-z_]+)(?::?(\\d+))?$");
    private static final Pattern TRANSFORMATION_PATTERN = Pattern.compile("transformation:\\[(.*?)]");
    private static final double ANCHOR_DISTANCE_EPSILON = 1.0E-9D;

    Result parse(Path createFunctionPath, String namespace) {
        try {
            String createFunction = Files.readString(createFunctionPath);
            Matcher itemDisplayMatcher = createItemDisplayPattern(namespace).matcher(createFunction);
            List<RawSkinPart> rawSkinParts = new ArrayList<>();
            int partCount = 0;

            while (itemDisplayMatcher.find()) {
                partCount++;

                String itemDisplayData = itemDisplayMatcher.group();
                String itemData = itemDisplayMatcher.group(1);
                int partIndex = Integer.parseInt(itemDisplayMatcher.group(2));
                RawSkinPart rawSkinPart = readSkinPart(itemDisplayData, itemData, partIndex);
                if (rawSkinPart != null) {
                    rawSkinParts.add(rawSkinPart);
                }
            }

            return new Result(partCount, assignSkinSegments(rawSkinParts));
        } catch (IOException exception) {
            Emote.LOGGER.warn("Failed to read parts from {}", createFunctionPath, exception);
            return new Result(0, List.of());
        }
    }

    private Pattern createItemDisplayPattern(String namespace) {
        String pattern = "\\{id:\"minecraft:item_display\",item:\\{(.*?)},.*?Tags:\\[[^]]*?\""
            + Pattern.quote(namespace)
            + "_(\\d+)\"[^]]*?]}";
        return Pattern.compile(pattern, Pattern.DOTALL);
    }

    private RawSkinPart readSkinPart(String itemDisplayData, String itemData, int partIndex) {
        if (!itemData.contains("id:\"minecraft:player_head\"")) {
            return null;
        }

        Matcher markerMatcher = PLAYER_SKIN_MARKER_PATTERN.matcher(itemData);
        if (!markerMatcher.find()) {
            return null;
        }

        double[] transformationValues = readTransformationValues(itemDisplayData);
        Matcher orderedMarkerMatcher = ORDERED_SKIN_MARKER_PATTERN.matcher(markerMatcher.group(1));
        if (!orderedMarkerMatcher.matches()) {
            return null;
        }
        PlayerSkinPart playerSkinPart = PlayerSkinPart.fromId(orderedMarkerMatcher.group(1));
        if (playerSkinPart == null) {
            return null;
        }
        Integer explicitOrder = orderedMarkerMatcher.group(2) == null
            ? null
            : Integer.parseInt(orderedMarkerMatcher.group(2));

        return new RawSkinPart(
            partIndex,
            playerSkinPart,
            readAnchorX(transformationValues),
            readAnchorY(transformationValues),
            readAnchorZ(transformationValues),
            readLocalY(transformationValues),
            readLocalYScale(transformationValues),
            explicitOrder
        );
    }

    private List<EmoteSkinPart> assignSkinSegments(List<RawSkinPart> rawSkinParts) {
        if (rawSkinParts.isEmpty()) {
            return List.of();
        }

        Map<PlayerSkinPart, List<RawSkinPart>> rawSkinPartMap = new java.util.EnumMap<>(PlayerSkinPart.class);
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
            List<RawSkinPart> partsForSkin = new ArrayList<>(entry.getValue());
            boolean hasExplicitOrder = isLimb(skinPart)
                && partsForSkin.stream().allMatch(part -> part.explicitOrder() != null);
            if (hasExplicitOrder) {
                partsForSkin.sort(
                    Comparator.comparingInt(RawSkinPart::explicitOrder)
                        .thenComparingInt(RawSkinPart::partIndex)
                );
            } else if (isLimb(skinPart) && limbRoot != null) {
                partsForSkin = orderConnectedParts(partsForSkin, limbRoot);
            } else {
                partsForSkin.sort(
                    Comparator.comparingInt(RawSkinPart::partIndex)
                        .thenComparing(Comparator.comparingDouble(RawSkinPart::localY).reversed())
                );
            }

            skinParts.addAll(createSkinParts(skinPart, partsForSkin));
        }

        skinParts.sort(Comparator.comparingInt(EmoteSkinPart::partIndex));
        return List.copyOf(skinParts);
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
            List<EmoteSkinPart> fullSkinParts = new ArrayList<>(partsForSkin.size());
            for (RawSkinPart rawSkinPart : partsForSkin) {
                fullSkinParts.add(new EmoteSkinPart(rawSkinPart.partIndex(), rawSkinPart.skinPart(), PlayerSkinSegment.FULL));
            }
            return fullSkinParts;
        }

        if (partsForSkin.size() > PlayerSkinSegment.SIDE_FACE_HEIGHT) {
            Emote.LOGGER.warn("Too many vertical skin segments for {}: {}", skinPart.id(), partsForSkin.size());
            List<EmoteSkinPart> fallbackSkinParts = new ArrayList<>(partsForSkin.size());
            for (RawSkinPart rawSkinPart : partsForSkin) {
                fallbackSkinParts.add(new EmoteSkinPart(rawSkinPart.partIndex(), rawSkinPart.skinPart(), PlayerSkinSegment.FULL));
            }
            return fallbackSkinParts;
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

    private int calculateSegmentEnd(int segmentStart, double accumulatedScale, double totalScale, int remainingPartCount) {
        int minEnd = segmentStart + 1;
        int maxEnd = Math.max(minEnd, PlayerSkinSegment.SIDE_FACE_HEIGHT - remainingPartCount);
        int suggestedEnd = (int) Math.round(accumulatedScale * PlayerSkinSegment.SIDE_FACE_HEIGHT / totalScale);
        if (suggestedEnd < minEnd) {
            return minEnd;
        }

        return Math.min(suggestedEnd, maxEnd);
    }

    private double[] readTransformationValues(String itemDisplayData) {
        Matcher transformationMatcher = TRANSFORMATION_PATTERN.matcher(itemDisplayData);
        if (!transformationMatcher.find()) {
            return null;
        }

        String[] values = transformationMatcher.group(1).split(",");
        if (values.length < 16) {
            return null;
        }

        double[] transformationValues = new double[16];
        try {
            for (int index = 0; index < transformationValues.length; index++) {
                transformationValues[index] = parseMatrixNumber(values[index]);
            }
            return transformationValues;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double readLocalY(double[] transformationValues) {
        return transformationValues == null ? 0.0D : transformationValues[7];
    }

    private double readAnchorX(double[] transformationValues) {
        return transformationValues == null ? 0.0D : transformationValues[3] - transformationValues[1] * 0.25D;
    }

    private double readAnchorY(double[] transformationValues) {
        return transformationValues == null ? 0.0D : transformationValues[7] - transformationValues[5] * 0.25D;
    }

    private double readAnchorZ(double[] transformationValues) {
        return transformationValues == null ? 0.0D : transformationValues[11] - transformationValues[9] * 0.25D;
    }

    private double readLocalYScale(double[] transformationValues) {
        if (transformationValues == null) {
            return 1.0D;
        }

        double firstValue = transformationValues[1];
        double secondValue = transformationValues[5];
        double thirdValue = transformationValues[9];
        return Math.sqrt(firstValue * firstValue + secondValue * secondValue + thirdValue * thirdValue);
    }

    private double parseMatrixNumber(String value) {
        String normalizedValue = value.trim();
        if (normalizedValue.endsWith("f") || normalizedValue.endsWith("d")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }

        return Double.parseDouble(normalizedValue);
    }

    record Result(int partCount, List<EmoteSkinPart> skinParts) {
    }

    private record RawSkinPart(
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
