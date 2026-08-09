package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ActiveEmote(
    UUID playerUuid,
    ResourceKey<Level> levelKey,
    String id,
    Vec3 startPosition,
    PlaybackNodes nodes,
    TimelinePlayer timeline,
    EventPlayer events,
    List<EmoteSkinPart> skinParts,
    EmoteAnimation.PlayerBehavior playerBehavior,
    boolean wasInvisible
) {
    public ActiveEmote {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startPosition, "startPosition");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(events, "events");
        skinParts = List.copyOf(skinParts);
        Objects.requireNonNull(playerBehavior, "playerBehavior");
    }
}
