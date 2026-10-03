package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.playback.runtime.*;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import io.github.hanhy06.emote.skin.PlayerSkinProvider;
import io.github.hanhy06.emote.skin.model.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackPlacementTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({"0,10,20,27", "90,13,20,30", "180,10,20,33", "-90,7,20,30"})
    void externalPlacementRotatesNestedAnchorPositionsWithoutModelYawOffsets(float yaw, double x, double y, double z) throws Exception {
        Fixture fixture = fixture();
        Vec3 origin = new Vec3(10, 20, 30);
        var snapshot = fixture.session().playbackInfo();
        PlaybackPlacement placement = PlaybackPlacement.external(origin, yaw);
        assertTrue(fixture.manager().setPlacement(fixture.session().sessionId(), placement));
        assertPosition(new Vec3(x, y, z), fixture.session().nodeWorldPosition("child").orElseThrow());
        assertEquals(origin, fixture.session().placement().position());
        assertEquals(placement.yaw(), fixture.session().placement().yaw(), 0.0001F);
        assertEquals(PlaybackPlacement.Mode.EXTERNAL, fixture.session().placement().mode());
        assertEquals(Vec3.ZERO, snapshot.placement().position());
        assertEquals(PlaybackPlacement.Mode.PLAYER, snapshot.placement().mode());
        assertTrue(fixture.session().nodeWorldPosition("unknown").isEmpty());
        assertEquals(Vec3.ZERO, fixture.state().startPosition());
    }

    @Test
    void externalPlacementSurvivesNormalPlayerFrameUpdates() throws Exception {
        Fixture fixture = fixture();
        PlaybackPlacement external = PlaybackPlacement.external(new Vec3(100, 64, -30), 170);
        fixture.manager().setPlacement(fixture.session().sessionId(), external);
        fixture.manager().updatePlayerPlacement(fixture.session(), new Vec3(-10, 70, 2), -90, followsPlayer());
        assertEquals(external, fixture.session().placement());
        assertTrue(PlayerPlaybackManager.shouldStopForMovement(1, 0.1));
        assertEquals(Vec3.ZERO, fixture.state().startPosition());
        assertEquals(0, fixture.session().playback().currentTick());
    }

    @Test
    void playerModeStillFollowsMovementAndUsesAnimationRotationDeadzone() throws Exception {
        Fixture fixture = fixture();
        fixture.manager().updatePlayerPlacement(fixture.session(), new Vec3(5, 6, 7), 90, followsPlayer());
        assertEquals(new Vec3(5, 6, 7), fixture.session().placement().position());
        assertEquals(40, fixture.session().placement().yaw(), 0.0001F);
        fixture.manager().setPlacement(fixture.session().sessionId(), PlaybackPlacement.external(new Vec3(1, 2, 3), -90));
        fixture.session().setPlacementMode(PlaybackPlacement.Mode.PLAYER);
        fixture.manager().updatePlayerPlacement(fixture.session(), new Vec3(8, 9, 10), 0, followsPlayer());
        assertEquals(new Vec3(8, 9, 10), fixture.session().placement().position());
        assertEquals(-50, fixture.session().placement().yaw(), 0.0001F);
    }

    @Test
    void rejectsUnknownOrRetiredSessionIdsWithoutTouchingCurrentPlayback() throws Exception {
        Fixture fixture = fixture();
        UUID retired = UUID.randomUUID();
        assertNull(fixture.manager().findSession(retired));
        assertNull(fixture.manager().stop(retired, PlaybackStopReason.MANUAL));
        assertFalse(fixture.manager().setPlacement(retired, PlaybackPlacement.external(new Vec3(9, 9, 9), 90)));
        assertSame(fixture.session(), fixture.manager().findSession(fixture.session().sessionId()));
        assertEquals(PlaybackState.RUNNING, fixture.session().playbackState());
        assertEquals(Vec3.ZERO, fixture.session().placement().position());
    }

    @Test
    void stoppingBySessionIdRemovesOnlyThatSessionAndKeepsCallbackCleanupDeferred() throws Exception {
        Fixture fixture = fixture();
        Fixture unrelated = fixture();
        var invoking = PlaybackSession.class.getDeclaredField("invokingCallback");
        invoking.setAccessible(true);
        invoking.setBoolean(fixture.session(), true);
        fixture.engine().register(fixture.session(), PlaybackEngine.Lifecycle.NONE);
        fixture.engine().register(unrelated.session(), PlaybackEngine.Lifecycle.NONE);
        assertSame(fixture.session(), fixture.manager().stop(fixture.session().sessionId(), PlaybackStopReason.MANUAL));
        assertNull(fixture.engine().findSession(fixture.session().sessionId()));
        assertEquals(PlaybackState.CLOSING, fixture.session().playbackState());
        assertSame(unrelated.session(), fixture.engine().findSession(unrelated.session().sessionId()));
        assertEquals(PlaybackState.RUNNING, unrelated.session().playbackState());
        assertNull(fixture.manager().stop(fixture.session().sessionId(), PlaybackStopReason.MANUAL));
    }

    @Test
    void rejectsNonFinitePlacementInputs() {
        assertThrows(IllegalArgumentException.class, () -> PlaybackPlacement.external(new Vec3(Double.NaN, 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> PlaybackPlacement.external(Vec3.ZERO, Float.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> new PlayOptions(null));
        assertEquals(PlaybackPlacement.Mode.PLAYER, PlayOptions.createDefault().placement().mode());
    }

    private static EmotePlayerBehavior followsPlayer() {
        return new EmotePlayerBehavior(false, new EmotePlayerBehavior.StopConditions(0, true, true, true, true, true, true));
    }

    private static void assertPosition(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 0.0001);
        assertEquals(expected.y, actual.y, 0.0001);
        assertEquals(expected.z, actual.z, 0.0001);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() throws Exception {
        Map<String, EmoteAnimation.Node> definitions = Map.of(
            "root", new EmoteAnimation.AnchorNode(null, translation(1)),
            "child", new EmoteAnimation.AnchorNode("root", translation(2)));
        EmoteAnimation source = new EmoteAnimation(net.minecraft.resources.Identifier.parse("test:placement"),
            new EmoteMetadata("Placement", "test"), new EmoteAnimation.Settings(true, 0, 50, 1,
            EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0, 0)),
            EmoteAnimation.MolangPrograms.empty(), definitions, new EmoteAnimation.Timeline(5, Map.of(), EmoteAnimation.Events.empty()), List.of());
        PreparedEmote prepared = PreparedEmote.from(new LoadedAnimation(Path.of("placement.json"), "test", source));
        PlaybackNodes nodes = new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of(
            "root", new PlaybackNodes.NodeInstance("root", definitions.get("root"), null, null),
            "child", new PlaybackNodes.NodeInstance("child", definitions.get("child"), null, null)));
        PlaybackEngine engine = new PlaybackEngine();
        PlaybackPlayer animation = new PlaybackPlayer(prepared, new EntityTimelineTarget(prepared, nodes, engine.entities()));
        animation.start();
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, prepared.id(), nodes, animation, Map.of());
        session.setPlacementMode(PlaybackPlacement.Mode.PLAYER);
        PlayerPlaybackManager manager = new PlayerPlaybackManager(engine, new PlayerSkinManager(new PlayerSkinProvider() {
            public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> regions) { throw new UnsupportedOperationException(); }
            public void setListener(Listener listener) {}
            public void cancelPendingBakes() {}
            public void onConfigReload(Config config) {}
        }));
        UUID actorId = UUID.randomUUID();
        PlayerPlaybackState state = new PlayerPlaybackState(actorId, Vec3.ZERO, List.of(), false, EmotePlayerBehavior.createDefault());
        Class<?> playbackClass = Class.forName(PlayerPlaybackManager.class.getName() + "$PlayerPlayback");
        var constructor = playbackClass.getDeclaredConstructor(PlaybackSession.class, PlayerPlaybackState.class, ServerPlayer.class);
        constructor.setAccessible(true);
        var sessions = PlayerPlaybackManager.class.getDeclaredField("playerSessions");
        sessions.setAccessible(true);
        ((Map<UUID, Object>) sessions.get(manager)).put(actorId, constructor.newInstance(session, state, null));
        return new Fixture(manager, engine, session, state, actorId);
    }

    private static EmoteAnimation.LocalTransform translation(double z) {
        return new EmoteAnimation.LocalTransform(new EmoteAnimation.Vec3(0, 0, z), EmoteAnimation.Vec3.ZERO, new EmoteAnimation.Vec3(1, 1, 1));
    }

    private record Fixture(PlayerPlaybackManager manager, PlaybackEngine engine, PlaybackSession session, PlayerPlaybackState state, UUID actorId) {}
}
