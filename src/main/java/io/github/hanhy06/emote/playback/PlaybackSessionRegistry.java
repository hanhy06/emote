package io.github.hanhy06.emote.playback;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PlaybackSessionRegistry {
    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> participantSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partnerReservations = new ConcurrentHashMap<>();
    private int activeDisplayEntityCount;

    void register(PlaybackSession session) {
        PlaybackSession previous = this.sessions.putIfAbsent(session.sessionId(), session);
        if (previous != null) {
            throw new IllegalStateException("Playback session is already registered: " + session.sessionId());
        }
        for (PlaybackParticipant participant : session.participants()) {
            registerParticipant(session, participant.playerUuid());
        }
        this.activeDisplayEntityCount += session.nodes().displayEntityCount();
    }

    void reservePartner(PlaybackSession session, UUID playerUuid) {
        requireRegistered(session);
        UUID previous = this.partnerReservations.putIfAbsent(playerUuid, session.sessionId());
        if (previous != null) {
            throw new IllegalStateException("Player already has a partner reservation: " + playerUuid);
        }
    }

    void activatePartner(PlaybackSession session, UUID playerUuid) {
        requireRegistered(session);
        if (!this.partnerReservations.remove(playerUuid, session.sessionId())) {
            throw new IllegalStateException("Player is not reserved for playback session: " + playerUuid);
        }
        registerParticipant(session, playerUuid);
    }

    void releasePartner(PlaybackSession session, UUID playerUuid) {
        this.partnerReservations.remove(playerUuid, session.sessionId());
    }

    @Nullable PlaybackSession findParticipant(UUID playerUuid) {
        return find(this.participantSessions, playerUuid);
    }

    @Nullable PlaybackSession findReservation(UUID playerUuid) {
        return find(this.partnerReservations, playerUuid);
    }

    Collection<PlaybackSession> sessions() {
        return this.sessions.values();
    }

    boolean isEmpty() {
        return this.sessions.isEmpty();
    }

    int activeDisplayEntityCount() {
        return this.activeDisplayEntityCount;
    }

    boolean contains(PlaybackSession session) {
        return this.sessions.get(session.sessionId()) == session;
    }

    boolean remove(PlaybackSession session) {
        if (!this.sessions.remove(session.sessionId(), session)) {
            return false;
        }
        this.activeDisplayEntityCount -= session.nodes().displayEntityCount();
        for (PlaybackParticipant participant : session.participants()) {
            this.participantSessions.remove(participant.playerUuid(), session.sessionId());
        }
        PlaybackParticipant reservedPartner = session.reservedPartner();
        if (reservedPartner != null) {
            this.partnerReservations.remove(reservedPartner.playerUuid(), session.sessionId());
        }
        return true;
    }

    private void registerParticipant(PlaybackSession session, UUID playerUuid) {
        UUID previous = this.participantSessions.putIfAbsent(playerUuid, session.sessionId());
        if (previous != null) {
            throw new IllegalStateException("Player already participates in a playback session: " + playerUuid);
        }
    }

    private @Nullable PlaybackSession find(Map<UUID, UUID> index, UUID playerUuid) {
        UUID sessionId = index.get(playerUuid);
        return sessionId == null ? null : this.sessions.get(sessionId);
    }

    private void requireRegistered(PlaybackSession session) {
        if (!contains(session)) {
            throw new IllegalStateException("Playback session is not registered: " + session.sessionId());
        }
    }
}
