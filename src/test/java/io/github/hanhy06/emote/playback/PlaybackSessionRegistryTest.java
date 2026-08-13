package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.emote.EmoteSequence;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredEmoteFixture;
import io.github.hanhy06.emote.emote.RegisteredSequence;
import net.minecraft.SharedConstants;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSessionRegistryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void keepsPartnerReservationsSeparateUntilActivation() {
        RegisteredEmote emote = RegisteredEmoteFixture.create("test:registry", "Registry");
        PlaybackSession session = session(emote);
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        PlaybackParticipant partner = participant(ParticipantRole.PARTNER);
        registry.register(session);

        session.reservePartner(partner);
        registry.reservePartner(session, partner.playerUuid());

        assertNull(registry.findParticipant(partner.playerUuid()));
        assertSame(session, registry.findReservation(partner.playerUuid()));

        session.activateReservedPartner(timeline(emote), events(emote));
        registry.activatePartner(session, partner.playerUuid());

        assertSame(session, registry.findParticipant(partner.playerUuid()));
        assertNull(registry.findReservation(partner.playerUuid()));
    }

    @Test
    void removingSessionClearsParticipantAndReservationIndexes() {
        RegisteredEmote emote = RegisteredEmoteFixture.create("test:remove", "Remove");
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
    }

    private static PlaybackSession session(RegisteredEmote emote) {
        EmoteSequence source = new EmoteSequence(
            Path.of("registry.json"),
            Identifier.parse("test:registry"),
            new EmoteMetadata("Registry", "Registry"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(emote.animation().id(), 1))
        );
        RegisteredSequence.Branch branch = new RegisteredSequence.Branch(List.of(
            new RegisteredSequence.EmoteStep(List.of(new RegisteredSequence.Choice(emote, 0)), 1)
        ));
        RegisteredSequence sequence = new RegisteredSequence(
            source,
            new RegisteredSequence.CollaborativePlayback(emote, 20, branch, branch),
            emote
        );
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            sequence.id(),
            emote.id(),
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0.0F), Map.of()),
            timeline(emote),
            events(emote),
            EmotePlayerBehavior.createDefault(),
            participant(ParticipantRole.INITIATOR),
            sequence
        );
    }

    private static TimelinePlayer timeline(RegisteredEmote emote) {
        return new TimelinePlayer(
            emote.playbackPlan(),
            new PlaybackNodes(RootTransform.create(Vec3.ZERO, 0.0F), Map.of()),
            new PlaybackEntityController()
        );
    }

    private static EventPlayer events(RegisteredEmote emote) {
        return new EventPlayer(emote.playbackPlan(), ignored -> {
        });
    }

    private static PlaybackParticipant participant(ParticipantRole role) {
        return new PlaybackParticipant(UUID.randomUUID(), role, Vec3.ZERO, List.of(), false);
    }
}
