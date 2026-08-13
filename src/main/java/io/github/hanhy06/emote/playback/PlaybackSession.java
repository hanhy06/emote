package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.content.PreparedSequence;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class PlaybackSession {
    private final UUID sessionId;
    private final ResourceKey<Level> levelKey;
    private final String id;
    private final String animationId;
    private final PlaybackNodes nodes;
    private TimelinePlayer timeline;
    private EventPlayer events;
    private final EmotePlayerBehavior playerBehavior;
    private final @Nullable PreparedSequence collaborativeSequence;
    private final EnumMap<ParticipantRole, PlaybackParticipant> participants = new EnumMap<>(ParticipantRole.class);

    private State state;
    private int remainingTimeoutTicks;
    private @Nullable PlaybackParticipant reservedPartner;

    public PlaybackSession(
        UUID sessionId,
        ResourceKey<Level> levelKey,
        String id,
        String animationId,
        PlaybackNodes nodes,
        TimelinePlayer timeline,
        EventPlayer events,
        EmotePlayerBehavior playerBehavior,
        PlaybackParticipant initiator,
        @Nullable PreparedSequence collaborativeSequence
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.id = Objects.requireNonNull(id, "id");
        this.animationId = Objects.requireNonNull(animationId, "animationId");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.events = Objects.requireNonNull(events, "events");
        this.playerBehavior = Objects.requireNonNull(playerBehavior, "playerBehavior");
        this.collaborativeSequence = collaborativeSequence;
        this.state = collaborativeSequence == null ? State.SOLO : State.OFFERING;
        this.remainingTimeoutTicks = collaborativeSequence == null ? 0 : collaborativeSequence.collaboration().timeoutTicks();
        addParticipant(Objects.requireNonNull(initiator, "initiator"));
        if (initiator.role() != ParticipantRole.INITIATOR) {
            throw new IllegalArgumentException("A playback session must start with an initiator");
        }
    }

    void addParticipant(PlaybackParticipant participant) {
        PlaybackParticipant previous = this.participants.putIfAbsent(participant.role(), participant);
        if (previous != null) {
            throw new IllegalStateException("Participant role is already occupied: " + participant.role());
        }
    }

    public UUID sessionId() {
        return this.sessionId;
    }

    public ResourceKey<Level> levelKey() {
        return this.levelKey;
    }

    public String id() {
        return this.id;
    }

    public String animationId() {
        return this.animationId;
    }

    public PlaybackNodes nodes() {
        return this.nodes;
    }

    public TimelinePlayer timeline() {
        return this.timeline;
    }

    public EventPlayer events() {
        return this.events;
    }

    public EmotePlayerBehavior playerBehavior() {
        return this.playerBehavior;
    }

    public PlaybackParticipant initiator() {
        return this.participants.get(ParticipantRole.INITIATOR);
    }

    public PlaybackParticipant participant(UUID playerUuid) {
        for (PlaybackParticipant participant : this.participants.values()) {
            if (participant.playerUuid().equals(playerUuid)) {
                return participant;
            }
        }
        return null;
    }

    boolean collaborative() {
        return this.collaborativeSequence != null;
    }

    PreparedSequence collaborativeSequence() {
        if (this.collaborativeSequence == null) {
            throw new IllegalStateException("Solo sessions do not have a collaborative sequence");
        }
        return this.collaborativeSequence;
    }

    public State state() {
        return this.state;
    }

    void enterWaiting() {
        if (this.state != State.OFFERING || this.reservedPartner != null) {
            throw new IllegalStateException("Only an unreserved offer can start waiting for a partner");
        }
        this.state = State.WAITING;
    }

    boolean tickTimeout() {
        if (this.state != State.WAITING) {
            throw new IllegalStateException("Session is not waiting for a partner");
        }
        return --this.remainingTimeoutTicks <= 0;
    }

    void reservePartner(PlaybackParticipant participant) {
        if (!acceptsPartner()) {
            throw new IllegalStateException("Session is not accepting a partner");
        }
        if (participant.role() != ParticipantRole.PARTNER) {
            throw new IllegalArgumentException("Reserved participant must use the partner role");
        }
        this.reservedPartner = participant;
    }

    boolean acceptsPartner() {
        return (this.state == State.OFFERING || this.state == State.WAITING) && this.reservedPartner == null;
    }

    public @Nullable PlaybackParticipant reservedPartner() {
        return this.reservedPartner;
    }

    PlaybackParticipant activateReservedPartner(TimelinePlayer timeline, EventPlayer events) {
        if (this.state != State.OFFERING && this.state != State.WAITING) {
            throw new IllegalStateException("Session cannot activate a partner in state " + this.state);
        }
        PlaybackParticipant participant = Objects.requireNonNull(this.reservedPartner, "reservedPartner");
        this.reservedPartner = null;
        addParticipant(participant);
        replacePlayback(timeline, events, State.MATCHED);
        return participant;
    }

    @Nullable PlaybackParticipant releaseReservedPartner() {
        PlaybackParticipant participant = this.reservedPartner;
        this.reservedPartner = null;
        return participant;
    }

    void beginTimeout(TimelinePlayer timeline, EventPlayer events) {
        if (this.state != State.WAITING || this.reservedPartner != null) {
            throw new IllegalStateException("Only an unreserved waiting session can time out");
        }
        replacePlayback(timeline, events, State.TIMEOUT);
    }

    private void replacePlayback(TimelinePlayer timeline, EventPlayer events, State state) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.events = Objects.requireNonNull(events, "events");
        this.state = Objects.requireNonNull(state, "state");
    }

    public Collection<PlaybackParticipant> participants() {
        return Collections.unmodifiableCollection(this.participants.values());
    }

    public Map<ParticipantRole, PlaybackParticipant> participantsByRole() {
        return Collections.unmodifiableMap(this.participants);
    }

    public enum State {
        SOLO,
        OFFERING,
        WAITING,
        MATCHED,
        TIMEOUT
    }
}
