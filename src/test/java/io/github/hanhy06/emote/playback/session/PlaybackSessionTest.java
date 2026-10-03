package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.*;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import io.github.hanhy06.emote.playback.CallbackRegistry;
import io.github.hanhy06.emote.playback.PlayerPlaybackState;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSessionTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actorFreePlaybackRunsCallbacksAndClosesWithoutPlayerLookup() throws Exception {
        PlaybackSession session = fixture().session();
        CallbackRegistry registry = new CallbackRegistry();
        Identifier id = Identifier.parse("test:actor_free");
        List<String> calls = new ArrayList<>();
        registry.register(id, new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                assertTrue(context.actor("actor").isEmpty());
                assertTrue(context.actor("missing").isEmpty());
                assertEquals(Vec3.ZERO, context.rootPosition());
                calls.add("start");
            }
            public void onTick(PlaybackContext context) { calls.add("tick"); }
            public void onClose(PlaybackContext context) {
                assertTrue(context.actor("actor").isEmpty());
                assertEquals(PlaybackStopReason.MANUAL, context.stopReason().orElseThrow());
                calls.add("close");
            }
        });
        session.bindCallbacks(registry.resolve(List.of(new EmoteAnimation.Callback(id, ""))), Map.of(), 0);
        session.startPlayback();
        assertTrue(session.tick(1));
        session.tickCallbacks();
        assertTrue(session.beginClose(PlaybackStopReason.MANUAL));
        session.playback().stop(PlaybackStopReason.MANUAL);
        session.closeCallbacks();
        session.completeClose();
        assertEquals(List.of("start", "tick", "close"), calls);
        assertEquals(PlaybackState.CLOSED, session.playbackState());
    }

    @Test
    void callbacksKeepStatePerSessionAndUnregisterOnlyAffectsFuturePlayback() throws Exception {
        CallbackRegistry registry = new CallbackRegistry();
        Identifier id = Identifier.parse("test:partner");
        List<PlaybackContext> contexts = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        var registration = registry.register(id, new EmoteCallbacks() {
            public void onStart(PlaybackContext context) { contexts.add(context); context.setUserState(new Object()); calls.add("start"); }
            public void onTick(PlaybackContext context) { calls.add("tick:" + context.elapsedTicks()); }
            public void onClose(PlaybackContext context) { calls.add("close:" + context.stopReason().orElseThrow()); }
        });
        assertThrows(IllegalArgumentException.class, () -> registry.register(id, new EmoteCallbacks() {}));
        PlaybackSession first = fixture().session();
        PlaybackSession second = fixture().session();
        first.bindCallbacks(registry.resolve(List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(id, ""))), Map.of(), 100);
        second.bindCallbacks(registry.resolve(List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(id, ""))), Map.of(), 100);
        first.startPlayback();
        second.startPlayback();
        assertNotSame(contexts.get(0).userState(), contexts.get(1).userState());
        assertTrue(registration.unregister());
        assertFalse(registration.unregister());
        var replacement = registry.register(id, new EmoteCallbacks() {});
        assertFalse(registration.isRegistered());
        assertTrue(replacement.isRegistered());
        assertFalse(first.tick(100));
        assertTrue(first.tick(101));
        assertFalse(first.tick(101));
        first.tickCallbacks();
        assertTrue(first.beginClose(PlaybackStopReason.MANUAL));
        first.closeCallbacks();
        first.completeClose();
        assertFalse(first.beginClose(PlaybackStopReason.FINISHED));
        assertFalse(first.tick(102));
        assertNull(contexts.get(0).userState());
        assertNotNull(contexts.get(1).userState());
        assertEquals(List.of("start", "start", "tick:1", "close:MANUAL"), calls);
    }

    @Test
    void callbackStopDefersCleanupUntilTheFunctionReturns() throws Exception {
        PlaybackSession session = fixture().session();
        List<String> calls = new ArrayList<>();
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                calls.add("start");
                assertTrue(session.isInvokingCallback());
                assertTrue(session.beginClose(PlaybackStopReason.MANUAL));
                assertEquals(PlaybackState.CLOSING, session.playbackState());
                assertFalse(session.tick(101));
                session.tickCallbacks();
                assertTrue(session.deferCleanup(() -> {
                    session.closeCallbacks();
                    session.completeClose();
                }));
                calls.add("returned");
            }
            public void onClose(PlaybackContext context) { calls.add("close"); }
        }, "")), Map.of(), 100);
        session.startPlayback();
        assertEquals(List.of("start", "returned", "close"), calls);
        assertFalse(session.isInvokingCallback());
        assertEquals(PlaybackState.CLOSED, session.playbackInfo(UUID.randomUUID()).state());
    }

    @Test
    void closeRunsOnceForEveryReasonAndCleanupSurvivesCloseFailure() throws Exception {
        for (PlaybackStopReason reason : PlaybackStopReason.values()) {
            PlaybackSession session = fixture().session();
            List<PlaybackStopReason> closed = new ArrayList<>();
            session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
                public void onClose(PlaybackContext context) {
                    closed.add(context.stopReason().orElseThrow());
                    throw new IllegalStateException("expected close failure");
                }
            }, "")), Map.of(), 100);
            session.startPlayback();
            assertTrue(session.beginClose(reason));
            session.closeCallbacks();
            session.completeClose();
            assertFalse(session.beginClose(reason));
            assertEquals(List.of(reason), closed);
            assertEquals(PlaybackState.CLOSED, session.playbackInfo(UUID.randomUUID()).state());
        }
    }

    @Test
    void callbackFailurePropagatesToEngineAndStillAllowsErrorCleanup() throws Exception {
        PlaybackSession session = fixture().session();
        List<PlaybackStopReason> closed = new ArrayList<>();
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onTick(PlaybackContext context) { throw new IllegalStateException("expected tick failure"); }
            public void onClose(PlaybackContext context) { closed.add(context.stopReason().orElseThrow()); }
        }, "")), Map.of(), 100);
        session.startPlayback();
        session.tick(101);
        assertThrows(IllegalStateException.class, session::tickCallbacks);
        assertFalse(session.deferCleanup(() -> fail("Not in callback")));
        session.beginClose(PlaybackStopReason.ERROR);
        session.closeCallbacks();
        session.completeClose();
        assertEquals(List.of(PlaybackStopReason.ERROR), closed);
    }

    @Test
    void commonCallbackReceivesEachStringAndKeepsEachUsageIndependent() throws Exception {
        CallbackRegistry registry = new CallbackRegistry();
        Identifier name = Identifier.parse("test:shared");
        List<PlaybackContext> contexts = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        registry.register(name, new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                contexts.add(context);
                context.setUserState(new Object());
                calls.add("start:" + context.payload());
            }
            public void onTick(PlaybackContext context) { calls.add("tick:" + context.payload()); }
            public void onClose(PlaybackContext context) { calls.add("close:" + context.payload()); }
        });
        var definitions = List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(name, "one"),
            new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(name, "two"));
        PlaybackSession first = fixture().session();
        PlaybackSession second = fixture().session();
        first.bindCallbacks(registry.resolve(definitions), Map.of(), 100);
        second.bindCallbacks(registry.resolve(definitions), Map.of(), 100);
        first.startPlayback();
        second.startPlayback();
        assertEquals(4, contexts.size());
        assertNotSame(contexts.get(0).userState(), contexts.get(1).userState());
        assertNotSame(contexts.get(0).userState(), contexts.get(2).userState());
        first.tick(101);
        first.tickCallbacks();
        first.beginClose(PlaybackStopReason.MANUAL);
        first.closeCallbacks();
        first.completeClose();
        assertNull(contexts.get(0).userState());
        assertNull(contexts.get(1).userState());
        assertNotNull(contexts.get(2).userState());
        assertEquals(List.of("start:one", "start:two", "start:one", "start:two", "tick:one", "tick:two", "close:one", "close:two"), calls);
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(List.of(
            new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(Identifier.parse("test:missing"), ""))));
    }

    @Test
    void stopDuringFirstStartDoesNotStartOrCloseLaterCallbacks() throws Exception {
        PlaybackSession session = fixture().session();
        List<String> calls = new ArrayList<>();
        var first = new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                calls.add("first-start");
                session.beginClose(PlaybackStopReason.MANUAL); session.deferCleanup(() -> { session.closeCallbacks(); session.completeClose(); });
            }
            public void onClose(PlaybackContext context) { calls.add("first-close"); }
        };
        var second = new EmoteCallbacks() {
            public void onStart(PlaybackContext context) { calls.add("second-start"); }
            public void onClose(PlaybackContext context) { calls.add("second-close"); }
        };
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(first, "a"), new CallbackRegistry.Binding(second, "b")), Map.of(), 100);
        session.startPlayback();
        assertEquals(List.of("first-start", "first-close"), calls);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "0,2", "1,2"})
    void repeatedAnimationsKeepIndependentStateAndCloseBeforeTheNextStart(int transitionTicks, int waitTicks) {
        PreparedEmote template = PreparedEmoteFixture.create("test:repeated", "Repeated");
        EmoteAnimation source = template.model();
        var event = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO), List.of("start-command"));
        var stop = new EmoteAnimation.Event(event.source(), event.origin(), List.of("stop-command"));
        var animation = new EmoteAnimation(source.id(), source.metadata(), source.settings(), source.molang(), source.nodes(),
            new EmoteAnimation.Timeline(2, Map.of(), new EmoteAnimation.Events(List.of(event), List.of(), List.of(), List.of(stop))),
            List.of(new EmoteAnimation.Callback(Identifier.parse("test:animation"), "node")));
        PreparedEmote repeated = PreparedEmote.from(new LoadedAnimation(Path.of("repeated.json"), "test", animation));
        List<EmoteSequence.Step> steps = new ArrayList<>();
        steps.add(new EmoteSequence.AnimationStep(source.id(), 1));
        if (waitTicks > 0) steps.add(new EmoteSequence.WaitStep(waitTicks));
        steps.add(new EmoteSequence.AnimationStep(source.id(), 1, transitionTicks));
        EmoteSequence sequence = new EmoteSequence(Identifier.parse("test:sequence"), source.metadata(),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            steps, List.of());
        PreparedEmote compiled = PreparedSequence.resolve(sequence, Map.of(repeated.id(), repeated)).compiledEmote();
        List<String> calls = new ArrayList<>();
        List<PlaybackContext> contexts = new ArrayList<>();
        PlaybackPlayer player = new PlaybackPlayer(compiled, new EmptyTimelineTarget());
        player.bindEvents(command -> calls.addAll(command.event().commands()));
        player.start();
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, compiled.id(),
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of()), player, Map.of());
        var callbacks = new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                contexts.add(context);
                context.setUserState(new Object());
                calls.add("start:" + context.animationTick() + ":" + context.elapsedTicks());
            }
            public void onTick(PlaybackContext context) {
                assertNotNull(context.userState());
                calls.add("tick:" + context.animationTick() + ":" + context.elapsedTicks());
            }
            public void onClose(PlaybackContext context) {
                assertNotNull(context.userState());
                calls.add("close:" + context.animationTick() + ":" + context.stopReason().orElseThrow());
            }
        };
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onStart(PlaybackContext context) { context.setUserState("root"); }
            public void onTick(PlaybackContext context) { assertEquals("root", context.userState()); }
            public void onClose(PlaybackContext context) { calls.add("root-close"); }
        }, "")), Map.of(repeated, List.of(new CallbackRegistry.Binding(callbacks, "node"))), 100);
        session.startPlayback();
        for (int tick = 1; tick <= compiled.durationTicks(); tick++) {
            assertTrue(session.tick(100 + tick));
            player.advance();
            session.tickCallbacks();
        }
        assertTrue(session.beginClose(PlaybackStopReason.FINISHED));
        player.stop(PlaybackStopReason.FINISHED);
        session.closeCallbacks();
        session.completeClose();
        assertEquals(List.of("start-command", "start:0:0", "tick:1:1", "tick:2:2", "stop-command", "close:2:FINISHED",
            "start-command", "start:0:0", "tick:1:1", "tick:2:2", "stop-command", "close:2:FINISHED", "root-close"), calls);
        assertEquals(2, contexts.size());
        assertNotSame(contexts.get(0), contexts.get(1));
        assertNull(contexts.get(0).userState());
        assertNull(contexts.get(1).userState());
    }

    @Test
    void startCallbackSeesInitialVisibilityAndCommandsAndFinalTickRunsBeforeClose() {
        EmoteAnimation source = PreparedEmoteFixture.create("test:prepared", "Prepared").model();
        var start = new EmoteAnimation.Event(new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO), List.of("start-command"));
        var stop = new EmoteAnimation.Event(start.source(), start.origin(), List.of("stop-command"));
        var definition = new EmoteAnimation(source.id(), source.metadata(), source.settings(), source.molang(), source.nodes(),
            new EmoteAnimation.Timeline(1, Map.of(), new EmoteAnimation.Events(List.of(start), List.of(), List.of(), List.of(stop))), List.of());
        PreparedEmote prepared = PreparedEmote.from(new LoadedAnimation(Path.of("prepared.json"), "test", definition));
        EmptyTimelineTarget target = new EmptyTimelineTarget();
        PlaybackPlayer player = new PlaybackPlayer(prepared, target);
        List<String> calls = new ArrayList<>();
        player.bindEvents(event -> calls.addAll(event.event().commands()));
        player.start();
        player.deferInitialVisibility();
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), Level.OVERWORLD, prepared.id(),
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0), Map.of()), player, Map.of());
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                assertTrue(target.visibility.get("root"));
                assertEquals(List.of("start-command"), calls);
                calls.add("start-callback");
            }
            public void onTick(PlaybackContext context) { calls.add("tick:" + context.animationTick()); }
            public void onClose(PlaybackContext context) { calls.add("close-callback"); }
        }, "")), Map.of(), 100);
        session.startPlayback();
        session.tick(101);
        assertEquals(PlaybackPlayer.AdvanceResult.FINISHED, player.advance());
        session.tickCallbacks();
        session.beginClose(PlaybackStopReason.FINISHED);
        player.stop(PlaybackStopReason.FINISHED);
        session.closeCallbacks();
        session.completeClose();
        assertEquals(List.of("start-command", "start-callback", "tick:1", "stop-command", "close-callback"), calls);
    }

    private SessionFixture fixture() throws Exception {
        PreparedEmote offer = PreparedEmoteFixture.create("test:offer", "Offer");
        PlaybackSession session = new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            offer.id(),
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0.0F), Map.of()),
            timeline(offer), Map.of()
        );
        session.playback().start();
        return new SessionFixture(session, offer);
    }

    private static PlayerPlaybackState participant() {
        return new PlayerPlaybackState(UUID.randomUUID(), Vec3.ZERO, List.of(), false, EmotePlayerBehavior.createDefault());
    }

    private static PlaybackPlayer timeline(PreparedEmote emote) {
        PlaybackPlayer animation = new PlaybackPlayer(emote, new EmptyTimelineTarget());
        animation.bindEvents(ignored -> {
        });
        return animation;
    }

    private record SessionFixture(PlaybackSession session, PreparedEmote offer) {
    }

    private static final class EmptyTimelineTarget implements PlaybackPlayer.TimelineTarget {
        private final Map<String, Boolean> visibility = new java.util.HashMap<>();
        @Override
        public Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform) {
            return new Transformation(new org.joml.Matrix4f());
        }

        @Override
        public void applyTransform(String nodeId, PreparedEmote.PreparedTransform transform, int interpolationDurationTicks) {
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.visibility.put(nodeId, visible);
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {

        }

        @Override
        public void resetAll() {
        }
    }
}
