package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.playback.session.PlaybackSession;

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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackEngineTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removingSessionClearsIndexesAndDisplayCount() {
        PreparedAnimation emote = PreparedAnimationFixture.create("test:remove", "Remove");
        PlaybackSession session = session(emote);
        PlaybackEngine engine = new PlaybackEngine();
        PlaybackEngine.Lifecycle lifecycle = new PlaybackEngine.Lifecycle() {};
        engine.register(session, lifecycle);
        assertThrows(IllegalStateException.class, () -> engine.register(session, lifecycle));
        assertSame(session, engine.findSession(session.sessionId()));
        assertEquals(1, engine.activeDisplayEntityCount());

        PlaybackEngine.ActivePlayback removed = engine.remove(session);
        assertSame(session, removed.session());
        assertSame(lifecycle, removed.lifecycle());

        assertEquals(0, engine.activeSessionCount());
        assertNull(engine.findSession(session.sessionId()));
        assertEquals(0, engine.activeDisplayEntityCount());
        assertNull(engine.remove(session));
        assertEquals(0, engine.activeDisplayEntityCount());
    }

    private static PlaybackSession session(PreparedAnimation emote) {
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            emote.id(),
            emote.id(),
            playbackNodes(),
            timeline(emote),
            Map.of()
        );
    }

    private static AnimationPlayer timeline(PreparedAnimation emote) {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
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

    private static PlaybackNodes playbackNodes() {
        EmoteAnimation.ItemNode node = new EmoteAnimation.ItemNode(
            true,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag(),
            "none",
            null
        );
        return new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of("display", new PlaybackNodes.NodeInstance("display", node, null, null))
        );
    }

}
