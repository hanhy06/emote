package io.github.hanhy06.emote.playback.session;

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
import io.github.hanhy06.emote.playback.PlaybackExecution;
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
    void handleCountsServerTicksAndFreezesWhilePaused() throws Exception {
        PlaybackSession session = fixture(20).session();
        PlaybackExecution execution = session.execution();
        bindExecution(session, new PlaybackSessionRegistry());
        assertFalse(execution.tick(100));
        assertTrue(execution.tick(101));
        assertFalse(execution.tick(101));
        assertEquals(1, execution.info().elapsedTicks());
        assertTrue(execution.pause());
        assertEquals(PlaybackState.PAUSED, execution.state());
        assertFalse(execution.tick(102));
        assertTrue(execution.resume());
        assertTrue(execution.tick(103));
        assertEquals(2, execution.info().elapsedTicks());
        assertEquals(session.sessionId(), new PlayResult.Success(execution).handle().sessionId());
    }

    @Test
    void cleanupRunsOnceInReverseOrderDespiteExceptions() throws Exception {
        PlaybackSession session = fixture(20).session();
        bindExecution(session, new PlaybackSessionRegistry());
        PlaybackExecution execution = session.execution();
        List<Integer> calls = new ArrayList<>();
        execution.onClose(() -> calls.add(1));
        execution.onClose(() -> {
            calls.add(2);
            assertEquals(PlaybackState.CLOSING, execution.state());
            assertThrows(IllegalStateException.class, () -> execution.onClose(() -> calls.add(99)));
            assertFalse(execution.stop());
            throw new IllegalStateException("expected cleanup failure");
        });
        execution.onClose(() -> calls.add(3));
        assertTrue(execution.beginClose(PlaybackStopReason.RELOAD));
        execution.completeClose();
        assertFalse(execution.beginClose(PlaybackStopReason.MANUAL));
        assertEquals(List.of(3, 2, 1), calls);
        assertEquals(PlaybackState.CLOSED, execution.state());
        assertEquals(PlaybackStopReason.RELOAD, execution.stopReason().orElseThrow());
        assertFalse(execution.tick(101));
    }

    @Test
    void oldHandleCannotStopReplacementSessionWithTheSamePlayer() throws Exception {
        PlaybackSession original = fixture(20).session();
        PlaybackSession replacement = new PlaybackSession(UUID.randomUUID(), original.levelKey(), original.id(),
            original.animationId(), original.nodes(), original.animation(), original.playerBehavior(), original.initiator(), null);
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        registry.register(original);
        bindExecution(original, registry);
        registry.remove(original);
        original.execution().beginClose(PlaybackStopReason.REPLACED);
        original.execution().completeClose();
        registry.register(replacement);
        bindExecution(replacement, registry);
        assertFalse(original.execution().stop());
        assertFalse(original.execution().finish());
        assertFalse(original.execution().pause());
        assertSame(replacement, registry.findParticipant(original.initiator().playerUuid()));
        assertSame(replacement, registry.findSession(replacement.sessionId()));
        assertTrue(replacement.execution().stop());
        assertNull(registry.findParticipant(original.initiator().playerUuid()));
    }

    @Test
    void pausedHandleCanFinishAndRetainsItsReason() throws Exception {
        PlaybackSession session = fixture(20).session();
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        registry.register(session);
        bindExecution(session, registry);
        assertTrue(session.execution().pause());
        assertTrue(session.execution().finish());
        assertEquals(PlaybackState.CLOSED, session.execution().state());
        assertEquals(PlaybackStopReason.FINISHED, session.execution().stopReason().orElseThrow());
        assertFalse(session.execution().finish());
    }

    private static void bindExecution(PlaybackSession session, PlaybackSessionRegistry registry) {
        session.execution().bind(new PlaybackExecution.Control() {
            public void requireServerThread() {}
            public void finish(PlaybackSession target) { close(target, PlaybackStopReason.FINISHED); }
            public void stop(PlaybackSession target) { close(target, PlaybackStopReason.MANUAL); }
            private void close(PlaybackSession target, PlaybackStopReason reason) {
                if (registry.remove(target)) {
                    target.execution().beginClose(reason);
                    target.execution().completeClose();
                }
            }
        }, 100);
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
            ))
        );
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyTransform(String nodeId, PreparedAnimation.PreparedTransform transform, int interpolationDurationTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetAll() {
        }
    }
}
