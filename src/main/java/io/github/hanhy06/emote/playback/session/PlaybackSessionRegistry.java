package io.github.hanhy06.emote.playback.session;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaybackSessionRegistry {
    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private int activeDisplayEntityCount;

    public void register(PlaybackSession session) {
        PlaybackSession previous = this.sessions.putIfAbsent(session.sessionId(), session);
        if (previous != null) {
            throw new IllegalStateException("Playback session is already registered: " + session.sessionId());
        }
        this.activeDisplayEntityCount += session.nodes().displayEntityCount();
    }

    public @Nullable PlaybackSession findSession(UUID sessionId) {
        return this.sessions.get(sessionId);
    }

    public Collection<PlaybackSession> sessions() {
        return this.sessions.values();
    }

    public boolean isEmpty() {
        return this.sessions.isEmpty();
    }

    public int activeDisplayEntityCount() {
        return this.activeDisplayEntityCount;
    }

    public int activeSessionCount() {
        return this.sessions.size();
    }

    public boolean contains(PlaybackSession session) {
        return this.sessions.get(session.sessionId()) == session;
    }

    public boolean remove(PlaybackSession session) {
        if (!this.sessions.remove(session.sessionId(), session)) {
            return false;
        }
        this.activeDisplayEntityCount -= session.nodes().displayEntityCount();
        return true;
    }

}
