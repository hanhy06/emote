package io.github.hanhy06.emote.test;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EmoteDefinitionFixture {
    private static final EmoteAnimation.Matrix IDENTITY = new EmoteAnimation.Matrix(List.of(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    ));

    private EmoteDefinitionFixture() {
    }

    public static EmoteDefinition create(String id, String name) {
        EmoteAnimation animation = new EmoteAnimation(
            Objects.requireNonNull(Identifier.tryParse(id)),
            new EmoteAnimation.Metadata(name, name + " description", true),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY)),
            new EmoteAnimation.Timeline(1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty())
        );
        return EmoteDefinition.create(new EmoteAnimation.Loaded(
            Path.of(id.replace(':', '_') + ".json"),
            "0000000000000000000000000000000000000000000000000000000000000000",
            animation
        ));
    }
}
