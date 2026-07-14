package io.github.hanhy06.emote.bdengine;

import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.bdengine.BDEngineSkinSegmentAssigner.RawSkinPart;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinPart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BDEngineCreateFunctionParser {
    private static final Pattern PLAYER_SKIN_MARKER_PATTERN = Pattern.compile("name\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORDERED_SKIN_MARKER_PATTERN = Pattern.compile("^emote:([a-z_]+)(?::?(\\d+))?$");
    private static final Pattern TRANSFORMATION_PATTERN = Pattern.compile("transformation:\\[(.*?)]");
    private final BDEngineSkinSegmentAssigner skinSegmentAssigner = new BDEngineSkinSegmentAssigner();

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

            return new Result(partCount, this.skinSegmentAssigner.assign(rawSkinParts));
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

}
