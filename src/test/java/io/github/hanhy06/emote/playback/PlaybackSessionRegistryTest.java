package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.EmoteSequence;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.content.PreparedEmoteFixture;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.runtime.SceneRootResolver;
import io.github.hanhy06.emote.playback.timeline.EventPlayer;
import io.github.hanhy06.emote.playback.timeline.PlaybackTrack;
import io.github.hanhy06.emote.playback.timeline.TimelinePlayer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
    void keepsPartnerReservationsSeparateUntilActivation() {
        PreparedEmote emote = PreparedEmoteFixture.create("test:registry", "Registry");
        PlaybackSession session = session(emote);
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);
        registry.register(session);

        assertEquals(1, registry.activeDisplayEntityCount());

        session.reservePartner(partner);
        registry.reservePartner(session, partner.playerUuid());

        assertNull(registry.findParticipant(partner.playerUuid()));
        assertSame(session, registry.findReservation(partner.playerUuid()));

        session.activateReservedPartner(new PlaybackTrack(timeline(emote), events(emote)));
        registry.activatePartner(session, partner.playerUuid());

        assertSame(session, registry.findParticipant(partner.playerUuid()));
        assertNull(registry.findReservation(partner.playerUuid()));
    }

    @Test
    void removingSessionClearsParticipantAndReservationIndexes() {
        PreparedEmote emote = PreparedEmoteFixture.create("test:remove", "Remove");
        PlaybackSession session = session(emote);
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);
        registry.register(session);
        session.reservePartner(partner);
        registry.reservePartner(session, partner.playerUuid());

        assertTrue(registry.remove(session));

        assertNull(registry.findParticipant(session.initiator().playerUuid()));
        assertNull(registry.findReservation(partner.playerUuid()));
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.activeDisplayEntityCount());
        assertTrue(!registry.remove(session));
        assertEquals(0, registry.activeDisplayEntityCount());
    }

    private static PlaybackSession session(PreparedEmote emote) {
        EmoteSequence source = new EmoteSequence(
            Path.of("registry.json"),
            Identifier.parse("test:registry"),
            new EmoteMetadata("Registry", "Registry"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(emote.animation().id(), 1))
        );
        PreparedSequence.Branch branch = new PreparedSequence.Branch(List.of(
            new PreparedSequence.EmoteStep(List.of(new PreparedSequence.AnimationChoice(emote, 0)), 1)
        ));
        PreparedSequence sequence = new PreparedSequence(
            source,
            new PreparedSequence.CollaborativePlayback(emote, 20, branch, branch),
            emote,
            emote
        );
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            sequence.id(),
            emote.id(),
            playbackNodes(),
            new PlaybackTrack(timeline(emote), events(emote)),
            EmotePlayerBehavior.createDefault(),
            participant(ParticipantRole.INITIATOR),
            sequence
        );
    }

    private static TimelinePlayer timeline(PreparedEmote emote) {
        return new TimelinePlayer(
            emote.compiledTimeline(),
            new PlaybackNodes(SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)), Map.of()),
            new PlaybackEntityController()
        );
    }

    private static EventPlayer events(PreparedEmote emote) {
        return new EventPlayer(emote.compiledTimeline(), ignored -> {
        });
    }

    private static PlaybackParticipant participant(ParticipantRole role) {
        return new PlaybackParticipant(UUID.randomUUID(), role, Vec3.ZERO, List.of(), false);
    }

    private static PlaybackNodes playbackNodes() {
        EmoteAnimation.ItemNode node = new EmoteAnimation.ItemNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            identityMatrix(),
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

    private static EmoteAnimation.Matrix identityMatrix() {
        return new EmoteAnimation.Matrix(List.of(
            1.0D, 0.0D, 0.0D, 0.0D,
            0.0D, 1.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 1.0D, 0.0D,
            0.0D, 0.0D, 0.0D, 1.0D
        ));
    }
}
