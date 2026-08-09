package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RegisteredEmoteFixture {
    private static final EmoteAnimation.Matrix IDENTITY = new EmoteAnimation.Matrix(List.of(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    ));

    private RegisteredEmoteFixture() {
    }

    public static RegisteredEmote create(String id, String name) {
        EmoteAnimation animation = new EmoteAnimation(
            Objects.requireNonNull(Identifier.tryParse(id)),
            new EmoteAnimation.Metadata(name, name + " description"),
            EmoteAnimation.PlayerBehavior.createDefault(),
            Map.of("root", new EmoteAnimation.AnchorNode(IDENTITY)),
            new EmoteAnimation.Timeline(1, EmoteAnimation.LoopMode.ONCE, 0, List.of(), EmoteAnimation.Events.empty())
        );
        return RegisteredEmote.from(new EmoteAnimation.Loaded(
            Path.of(id.replace(':', '_') + ".json"),
            "0000000000000000000000000000000000000000000000000000000000000000",
            animation
        ));
    }
}
