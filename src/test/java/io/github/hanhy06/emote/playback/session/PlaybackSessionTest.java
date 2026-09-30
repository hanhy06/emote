package io.github.hanhy06.emote.playback.session;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import com.mojang.brigadier.StringReader;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaybackState;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.EmoteSequence;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimationFixture;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.CallbackRegistry;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.runtime.SceneRootResolver;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
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
    void offerWaitsUntilTimeoutBranchStarts() throws Exception {
        SessionFixture fixture = fixture(2);
        PlaybackSession session = fixture.session();

        session.enterWaiting();

        assertEquals(PlaybackSession.State.WAITING, session.state());
        assertFalse(session.tickTimeout());
        assertTrue(session.tickTimeout());

        AnimationPlayer timeoutTimeline = timeline(fixture.offer());
        session.beginTimeout(timeoutTimeline);

        assertEquals(PlaybackSession.State.TIMEOUT, session.state());
        assertSame(timeoutTimeline, session.animation());
        assertFalse(session.acceptsPartner());
    }

    @Test
    void activatesReservedPartnerAndMatchedPlaybackTogether() throws Exception {
        SessionFixture fixture = fixture(20);
        PlaybackSession session = fixture.session();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);
        AnimationPlayer matchedTimeline = timeline(fixture.offer());

        session.reservePartner(partner);
        PlaybackParticipant activated = session.activateReservedPartner(matchedTimeline);

        assertSame(partner, activated);
        assertSame(partner, session.participant(partner.playerUuid()));
        assertNull(session.reservedPartner());
        assertEquals(PlaybackSession.State.MATCHED, session.state());
        assertSame(matchedTimeline, session.animation());
    }

    @Test
    void reusesReadOnlyParticipantViewsAndReflectsPartnerActivation() throws Exception {
        SessionFixture fixture = fixture(20);
        PlaybackSession session = fixture.session();
        var participantView = session.participants();
        var participantMapView = session.participantsByRole();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);

        session.reservePartner(partner);
        session.activateReservedPartner(timeline(fixture.offer()));

        assertSame(participantView, session.participants());
        assertSame(participantMapView, session.participantsByRole());
        assertTrue(participantView.contains(partner));
        assertSame(partner, participantMapView.get(ParticipantRole.PARTNER));
        assertThrows(UnsupportedOperationException.class, participantView::clear);
        assertThrows(UnsupportedOperationException.class, participantMapView::clear);
    }

    @Test
    void onlyUnreservedOffersCanEnterWaiting() throws Exception {
        PlaybackSession session = fixture(20).session();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);
        session.reservePartner(partner);

        assertThrows(IllegalStateException.class, session::enterWaiting);
        assertSame(partner, session.releaseReservedPartner());

        session.enterWaiting();
        assertTrue(session.acceptsPartner());
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
        PlaybackSession first = fixture(20).session();
        PlaybackSession second = fixture(20).session();
        first.bindCallbacks(registry.resolve(List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(id, ""))), 100);
        second.bindCallbacks(registry.resolve(List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(id, ""))), 100);
        first.startCallbacks();
        second.startCallbacks();
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
        first.completeClose();
        assertFalse(first.beginClose(PlaybackStopReason.FINISHED));
        assertFalse(first.tick(102));
        assertNull(contexts.get(0).userState());
        assertNotNull(contexts.get(1).userState());
        assertEquals(List.of("start", "start", "tick:1", "close:MANUAL"), calls);
    }

    @Test
    void callbackStopDefersCleanupUntilTheFunctionReturns() throws Exception {
        PlaybackSession session = fixture(20).session();
        List<String> calls = new ArrayList<>();
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                calls.add("start");
                assertTrue(session.deferCleanup(() -> {
                    session.beginClose(PlaybackStopReason.MANUAL);
                    session.completeClose();
                }));
                calls.add("returned");
            }
            public void onClose(PlaybackContext context) { calls.add("close"); }
        }, "")), 100);
        session.startCallbacks();
        assertEquals(List.of("start", "returned", "close"), calls);
        assertEquals(PlaybackState.CLOSED, session.playbackInfo(session.initiator().playerUuid()).state());
    }

    @Test
    void closeRunsOnceForEveryReasonAndCleanupSurvivesCloseFailure() throws Exception {
        for (PlaybackStopReason reason : PlaybackStopReason.values()) {
            PlaybackSession session = fixture(20).session();
            List<PlaybackStopReason> closed = new ArrayList<>();
            session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
                public void onClose(PlaybackContext context) {
                    closed.add(context.stopReason().orElseThrow());
                    throw new IllegalStateException("expected close failure");
                }
            }, "")), 100);
            session.startCallbacks();
            assertTrue(session.beginClose(reason));
            session.completeClose();
            assertFalse(session.beginClose(reason));
            assertEquals(List.of(reason), closed);
            assertEquals(PlaybackState.CLOSED, session.playbackInfo(session.initiator().playerUuid()).state());
        }
    }

    @Test
    void callbackFailurePropagatesToEngineAndStillAllowsErrorCleanup() throws Exception {
        PlaybackSession session = fixture(20).session();
        List<PlaybackStopReason> closed = new ArrayList<>();
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
            public void onTick(PlaybackContext context) { throw new IllegalStateException("expected tick failure"); }
            public void onClose(PlaybackContext context) { closed.add(context.stopReason().orElseThrow()); }
        }, "")), 100);
        session.startCallbacks();
        session.tick(101);
        assertThrows(IllegalStateException.class, session::tickCallbacks);
        assertFalse(session.deferCleanup(() -> fail("Not in callback")));
        session.beginClose(PlaybackStopReason.ERROR);
        session.completeClose();
        assertEquals(List.of(PlaybackStopReason.ERROR), closed);
    }

    @Test
    void matchedAndTimeoutBranchesKeepTheSessionLoopCallback() throws Exception {
        for (boolean matched : List.of(true, false)) {
            SessionFixture fixture = fixture(20);
            PlaybackSession session = fixture.session();
            List<String> calls = new ArrayList<>();
            session.bindCallbacks(List.of(new CallbackRegistry.Binding(new EmoteCallbacks() {
                public void onStart(PlaybackContext context) { calls.add("start"); }
                public void onLoop(PlaybackContext context) { calls.add("loop"); }
            }, "")), 100);
            session.startCallbacks();
            var source = fixture.offer().animation();
            var settings = source.settings();
            var loop = new io.github.hanhy06.emote.api.animation.EmoteAnimation(source.id(), source.metadata(),
                new io.github.hanhy06.emote.api.animation.EmoteAnimation.Settings(settings.standalone(), settings.cooldownTicks(),
                    settings.rotationDeadzone(), settings.displayInterpolationTicks(), settings.player(),
                    new io.github.hanhy06.emote.api.animation.EmoteAnimation.PlaybackSettings(
                        io.github.hanhy06.emote.api.animation.EmoteAnimation.LoopMode.LOOP, 0, 1, 0)),
                source.molang(), source.nodes(), source.timeline(), List.of());
            AnimationPlayer branch = timeline(PreparedAnimation.from(new io.github.hanhy06.emote.content.LoadedAnimation(Path.of("loop.json"), "test", loop)));
            branch.start();
            if (matched) {
                session.reservePartner(participant(ParticipantRole.PARTNER));
                session.activateReservedPartner(branch);
            } else {
                session.enterWaiting();
                session.beginTimeout(branch);
            }
            branch.startEvents();
            branch.advance();
            assertEquals(List.of("start", "loop"), calls);
        }
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
        PlaybackSession first = fixture(20).session();
        PlaybackSession second = fixture(20).session();
        first.bindCallbacks(registry.resolve(definitions), 100);
        second.bindCallbacks(registry.resolve(definitions), 100);
        first.startCallbacks();
        second.startCallbacks();
        assertEquals(4, contexts.size());
        assertNotSame(contexts.get(0).userState(), contexts.get(1).userState());
        assertNotSame(contexts.get(0).userState(), contexts.get(2).userState());
        first.tick(101);
        first.tickCallbacks();
        first.beginClose(PlaybackStopReason.MANUAL);
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
        PlaybackSession session = fixture(20).session();
        List<String> calls = new ArrayList<>();
        var first = new EmoteCallbacks() {
            public void onStart(PlaybackContext context) {
                calls.add("first-start");
                session.deferCleanup(() -> { session.beginClose(PlaybackStopReason.MANUAL); session.completeClose(); });
            }
            public void onClose(PlaybackContext context) { calls.add("first-close"); }
        };
        var second = new EmoteCallbacks() {
            public void onStart(PlaybackContext context) { calls.add("second-start"); }
            public void onClose(PlaybackContext context) { calls.add("second-close"); }
        };
        session.bindCallbacks(List.of(new CallbackRegistry.Binding(first, "a"), new CallbackRegistry.Binding(second, "b")), 100);
        session.startCallbacks();
        assertEquals(List.of("first-start", "first-close"), calls);
    }

    @Test
    void sequenceRootCallbacksSurviveOfferMatchTimeoutAndPartnerLayoutExpansion() throws Exception {
        SessionFixture fixture = fixture(20);
        EmoteSequence source = fixture.session().partnerSequence().source();
        var definitions = List.of(new io.github.hanhy06.emote.api.animation.EmoteAnimation.Callback(
            Identifier.parse("test:shared"), "sequence payload"));
        EmoteSequence withCallbacks = new EmoteSequence(source.sourcePath(), source.id(), source.metadata(),
            source.settings(), source.participants(), source.steps(), definitions);
        PreparedSequence prepared = PreparedSequence.resolve(withCallbacks, Map.of(fixture.offer().id(), fixture.offer()));
        assertEquals(definitions, prepared.compiledAnimation().animation().callbacks());
        assertEquals(definitions, prepared.compileMatch(new java.util.Random(0)).animation().callbacks());
        assertEquals(definitions, prepared.compileTimeout(new java.util.Random(0)).animation().callbacks());
    }

    private SessionFixture fixture(int timeoutTicks) throws Exception {
        PreparedAnimation offer = PreparedAnimationFixture.create("test:offer", "Offer");
        Identifier offerId = offer.animation().id();
        EmoteSequence source = new EmoteSequence(
            Path.of("partner.json"),
            Identifier.parse("test:partner"),
            new EmoteMetadata("Partner", "Partner"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            new EmoteSequence.Participants(
                new EmoteSequence.ParticipantPlacement(
                    Vec3Argument.vec3(false).parse(new StringReader("~ ~ ~")),
                    RotationArgument.rotation().parse(new StringReader("~ 0"))
                ),
                new EmoteSequence.ParticipantPlacement(
                    Vec3Argument.vec3(false).parse(new StringReader("^ ^ ^1.2")),
                    RotationArgument.rotation().parse(new StringReader("~180 0"))
                )
            ),
            List.of(new EmoteSequence.AwaitPartnerStep(
                offerId,
                timeoutTicks,
                List.of(new EmoteSequence.EmoteStep(offerId, 1)),
                List.of(new EmoteSequence.EmoteStep(offerId, 1))
            )), List.of());
        PreparedSequence sequence = PreparedSequence.resolve(source, Map.of(offer.id(), offer));
        PlaybackSession session = new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            sequence.id(),
            offer.id(),
            new PlaybackNodes(SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)), Map.of()),
            timeline(offer),
            sequence.playerBehavior(),
            participant(ParticipantRole.INITIATOR),
            sequence
        );
        return new SessionFixture(session, offer);
    }

    private static PlaybackParticipant participant(ParticipantRole role) {
        return new PlaybackParticipant(UUID.randomUUID(), role, Vec3.ZERO, List.of(), false);
    }

    private static AnimationPlayer timeline(PreparedAnimation emote) {
        AnimationPlayer animation = new AnimationPlayer(emote, new EmptyTimelineTarget());
        animation.bindEvents(ignored -> {
        });
        return animation;
    }

    private record SessionFixture(PlaybackSession session, PreparedAnimation offer) {
    }

    private static final class EmptyTimelineTarget implements AnimationPlayer.TimelineTarget {
        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            return new Transformation(new org.joml.Matrix4f());
        }

        @Override
        public void applyTransform(String nodeId, PreparedAnimation.PreparedTransform transform, int interpolationDurationTicks) {
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {
            
        }

        @Override
        public void resetAll() {
        }
    }
}
