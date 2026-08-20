package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PreparedEmoteFixture {
    private PreparedEmoteFixture() {
    }

    public static PreparedEmote create(String id, String name) {
        return create(id, name, true, EmotePlayerBehavior.createDefault());
    }

    public static PreparedEmote create(String id, String name, boolean standalone) {
        return create(id, name, standalone, EmotePlayerBehavior.createDefault());
    }

    public static PreparedEmote create(String id, String name, int cooldownTicks) {
        return create(id, name, true, EmotePlayerBehavior.createDefault(), cooldownTicks);
    }

    public static PreparedEmote create(String id, String name, boolean standalone, int cooldownTicks) {
        return create(id, name, standalone, EmotePlayerBehavior.createDefault(), cooldownTicks);
    }

    public static PreparedEmote create(
        String id,
        String name,
        EmotePlayerBehavior playerBehavior
    ) {
        return create(id, name, true, playerBehavior);
    }

    private static PreparedEmote create(
        String id,
        String name,
        boolean standalone,
        EmotePlayerBehavior playerBehavior
    ) {
        return create(id, name, standalone, playerBehavior, 0);
    }

    private static PreparedEmote create(
        String id,
        String name,
        boolean standalone,
        EmotePlayerBehavior playerBehavior,
        int cooldownTicks
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Objects.requireNonNull(Identifier.tryParse(id)),
            new EmoteMetadata(name, name + " description"),
            new EmoteAnimation.Settings(standalone, cooldownTicks, playerBehavior, new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0)),
            EmoteAnimation.MolangPrograms.empty(),
            Map.of("root", new EmoteAnimation.AnchorNode(EmoteAnimation.NodeSpace.SCENE, null, EmoteAnimation.LocalTransform.IDENTITY)),
            new EmoteAnimation.Timeline(1, Map.of(), EmoteAnimation.Events.empty())
        );
        return PreparedEmote.from(new LoadedAnimation(
            Path.of(id.replace(':', '_') + ".json"),
            "0000000000000000000000000000000000000000000000000000000000000000",
            animation
        ));
    }
}
