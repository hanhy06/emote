package io.github.hanhy06.emote.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.TimeArgument;

import java.util.Objects;

public final class MinecraftTime {
    private MinecraftTime() {
    }

    public static int parse(String value, int minimumTicks) {
        Objects.requireNonNull(value, "value");
        StringReader reader = new StringReader(value);
        try {
            int ticks = TimeArgument.time(minimumTicks).parse(reader);
            if (reader.canRead()) {
                throw new IllegalArgumentException("invalid trailing time input at position " + reader.getCursor());
            }
            return ticks;
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException(exception.getRawMessage().getString(), exception);
        }
    }
}
