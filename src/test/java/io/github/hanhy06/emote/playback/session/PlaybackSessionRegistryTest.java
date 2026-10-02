package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimationFixture;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.runtime.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSessionRegistryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removingSessionClearsIndexesAndDisplayCount() {
        PreparedAnimation emote = PreparedAnimationFixture.create("test:remove", "Remove");
        PlaybackSession session = session(emote);
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        registry.register(session);
        assertSame(session, registry.findParticipant(session.player().playerUuid()));
        assertSame(session, registry.findSession(session.sessionId()));
        assertEquals(1, registry.activeDisplayEntityCount());

        assertTrue(registry.remove(session));

        assertNull(registry.findParticipant(session.player().playerUuid()));
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.activeDisplayEntityCount());
        assertTrue(!registry.remove(session));
        assertEquals(0, registry.activeDisplayEntityCount());
    }

    private static PlaybackSession session(PreparedAnimation emote) {
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            emote.id(),
            emote.id(),
            playbackNodes(),
            timeline(emote),
            EmotePlayerBehavior.createDefault(),
            participant()
        );
    }

    private static AnimationPlayer timeline(PreparedAnimation emote) {
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of()
        );
        AnimationPlayer animation = new AnimationPlayer(emote, new EntityTimelineTarget(
            emote,
            nodes,
            new PlaybackEntityController()
        ));
        animation.bindEvents(ignored -> {
        });
        return animation;
    }

    private static PlaybackParticipant participant() {
        return new PlaybackParticipant(UUID.randomUUID(), Vec3.ZERO, List.of(), false);
    }

    private static PlaybackNodes playbackNodes() {
        EmoteAnimation.ItemNode node = new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        return new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of("display", new PlaybackNodes.NodeInstance("display", node, null, null))
        );
    }

}
