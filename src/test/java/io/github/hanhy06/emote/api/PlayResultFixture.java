package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import java.util.UUID;

public final class PlayResultFixture {
    public static final PlayResult SUCCESS = new PlayResult.Success(new PlaybackInfo(UUID.randomUUID(), UUID.randomUUID(),
        Identifier.parse("demo:test"), PlaybackState.RUNNING, 0, 0,
        new PlaybackPosition(0, null, null, PlaybackTimeline.Phase.ANIMATION, 0, Identifier.parse("demo:test"), 0),
        PlaybackPlacement.actor()));
    private PlayResultFixture() {}
}
