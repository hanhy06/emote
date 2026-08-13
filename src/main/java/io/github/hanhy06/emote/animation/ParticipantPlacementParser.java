package io.github.hanhy06.emote.animation;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.EmoteSequence;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

final class ParticipantPlacementParser {
    EmoteSequence.ParticipantPlacement parse(
        String positionText,
        String rotationText,
        String path,
        EmoteJsonReader jsonReader
    ) throws EmoteAnimationLoadException {
        Coordinates position = parsePosition(positionText, path + ".position", jsonReader);
        Coordinates rotation = parseRotation(rotationText, path + ".rotation", jsonReader);
        return new EmoteSequence.ParticipantPlacement(position, rotation);
    }

    private Coordinates parsePosition(String value, String path, EmoteJsonReader jsonReader)
        throws EmoteAnimationLoadException {
        Coordinates coordinates = parse(value, path, jsonReader, true);
        if (!coordinates.isXRelative() || !coordinates.isYRelative() || !coordinates.isZRelative()) {
            throw jsonReader.error(path, "must use only relative ~ or local ^ coordinates");
        }
        return coordinates;
    }

    private Coordinates parseRotation(String value, String path, EmoteJsonReader jsonReader)
        throws EmoteAnimationLoadException {
        return parse(value, path, jsonReader, false);
    }

    private Coordinates parse(String value, String path, EmoteJsonReader jsonReader, boolean position)
        throws EmoteAnimationLoadException {
        StringReader reader = new StringReader(value);
        try {
            Coordinates coordinates = position
                ? Vec3Argument.vec3(false).parse(reader)
                : RotationArgument.rotation().parse(reader);
            if (reader.canRead()) {
                throw jsonReader.error(path, "contains trailing input");
            }
            return coordinates;
        } catch (CommandSyntaxException exception) {
            throw jsonReader.error(path, "invalid Minecraft coordinates", exception);
        }
    }
}
