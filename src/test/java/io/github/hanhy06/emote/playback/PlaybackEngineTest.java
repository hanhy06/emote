package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.application.ApiEventDispatcher;
import io.github.hanhy06.emote.api.EmotePlaybackListener;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedEmoteFixture;
import io.github.hanhy06.emote.playback.runtime.EntityTimelineTarget;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
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
    @Test
    void actorPlacementRestoresTheCurrentOwnerPositionAfterExternalControl() {
        PreparedEmote emote = PreparedEmoteFixture.create("test:actor-placement", "Actor placement");
        var nodes = new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of());
        PlaybackEngine engine = new PlaybackEngine();
        var player = new PlaybackPlayer(emote, new EntityTimelineTarget(emote, nodes, engine.entities()));
        var session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, emote.id(), nodes, player, Map.of(), PlaybackPlacement.Mode.ACTOR);
        var actorRoot = new java.util.concurrent.atomic.AtomicReference<>(RootTransform.create(Vec3.ZERO, 0));
        engine.register(session, new PlaybackEngine.Lifecycle() {
            @Override public RootTransform resolveActorPlacement(PlaybackSession playback) { return actorRoot.get(); }
        });

        var external = PlaybackPlacement.external(new Vec3(100, 64, -30), 170);
        assertTrue(engine.setPlacement(session.sessionId(), external));
        actorRoot.set(RootTransform.create(new Vec3(7, 8, 9), -90));
        assertEquals(external, session.placement());
        assertTrue(engine.setPlacement(session.sessionId(), PlaybackPlacement.actor()));
        assertEquals(new PlaybackPlacement(PlaybackPlacement.Mode.ACTOR, new Vec3(7, 8, 9), -90), session.placement());
        assertTrue(engine.setPlacement(session.sessionId(), external));
        assertEquals(external, session.placement());
    }

    @Test
    void actorFreeSessionsUseTheSameIndexForPlacementEventsAndStopping() throws Exception {
        PreparedEmote emote = PreparedEmoteFixture.create("test:actor-free", "Actor free");
        var nodes = new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of());
        PlaybackEngine engine = new PlaybackEngine();
        var player = new PlaybackPlayer(emote, new EntityTimelineTarget(emote, nodes, engine.entities()));
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, emote.id(), nodes, player, Map.of(), PlaybackPlacement.Mode.EXTERNAL);
        ApiEventDispatcher dispatcher = new ApiEventDispatcher();
        java.util.List<PlaybackInfo> started = new java.util.ArrayList<>();
        dispatcher.addPlaybackListener(new EmotePlaybackListener() {
            public void onStarted(PlaybackInfo playback) { started.add(playback); }
        });
        engine.setStateListener(dispatcher);
        engine.register(session, PlaybackEngine.Lifecycle.NONE);
        engine.notifyStarted(session);

        assertEquals(1, started.size());
        assertNull(started.getFirst().playerUuid());
        assertEquals(session.sessionId(), started.getFirst().sessionId());
        assertEquals(PlaybackPlacement.Mode.EXTERNAL, session.placement().mode());
        assertThrows(IllegalArgumentException.class, () -> engine.setPlacement(session.sessionId(), PlaybackPlacement.actor()));
        PlaybackPlacement placement = PlaybackPlacement.external(new Vec3(10, 20, 30), 90);
        assertTrue(engine.setPlacement(session.sessionId(), placement));
        assertEquals(placement, engine.findSession(session.sessionId()).playbackInfo().placement());
        assertEquals(PlaybackPlacement.Mode.EXTERNAL, session.placement().mode());
        assertThrows(IllegalArgumentException.class, () -> engine.setPlacement(session.sessionId(), PlaybackPlacement.actor()));
        assertEquals(placement, session.placement());
        assertFalse(engine.setPlacement(UUID.randomUUID(), placement));

        var invoking = PlaybackSession.class.getDeclaredField("invokingCallback");
        invoking.setAccessible(true);
        invoking.setBoolean(session, true);
        assertSame(session, engine.stop(session.sessionId(), PlaybackStopReason.MANUAL));
        assertNull(engine.findSession(session.sessionId()));
        assertEquals(PlaybackState.CLOSING, session.playbackState());
        assertNull(engine.stop(session.sessionId(), PlaybackStopReason.MANUAL));
    }

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removingSessionClearsIndexesAndDisplayCount() {
        PreparedEmote emote = PreparedEmoteFixture.create("test:remove", "Remove");
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

    private static PlaybackSession session(PreparedEmote emote) {
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            emote.id(),
            playbackNodes(),
            timeline(emote),
            Map.of(), PlaybackPlacement.Mode.EXTERNAL
        );
    }

    private static PlaybackPlayer timeline(PreparedEmote emote) {
        PlaybackNodes nodes = new PlaybackNodes(
            RootTransform.create(Vec3.ZERO, 0.0F),
            Map.of()
        );
        PlaybackPlayer animation = new PlaybackPlayer(emote, new EntityTimelineTarget(
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
