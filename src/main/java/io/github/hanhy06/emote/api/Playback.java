package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface Playback {
    UUID getId();
    Identifier getEmoteId();
    EmoteInfo getEmote();
    PlaybackState getState();
    boolean isActive();
    Optional<PlaybackStopReason> getStopReason();

    /**
     * Returns the number of playback ticks applied since this session started.
     * This count starts at zero and does not rewind when seeking or looping.
     */
    long getElapsedTicks();

    /**
     * Returns the current position in the playback timeline, in ticks, or {@code null} before the first frame is applied.
     * The timeline includes start delays, transitions, waits and loop delays. Sequence repetitions advance through
     * the expanded timeline; a looping Animation returns to its loop start, including the start-delay offset.
     * This position follows playback progress independently of a custom Animation clock.
     */
    @Nullable Integer getTimelineTick();

    /**
     * Returns the active Animation's clip position, in ticks, or {@code null} outside an Animation segment.
     * This value follows the clip's clock, including a custom Molang clock, and excludes delays and transitions.
     */
    @Nullable Integer getAnimationTick();

    /**
     * Returns the current segment, Sequence step and active Animation, or {@code null} before the first frame is applied.
     * {@link PlaybackCursor#segmentTick()} is the playback position within the current segment: it starts at zero
     * on entry and advances independently of the Animation's clip clock.
     */
    @Nullable PlaybackCursor getCursor();

    /**
     * Returns the currently resolved timeline, or {@code null} before the first frame is applied.
     * Segment bounds use timeline ticks, with inclusive starts and exclusive ends. Its duration includes all delays,
     * transitions and waits, and is {@code null} while the end is unknown or playback is indefinite, such as LOOP or HOLD.
     * The Animation's declared clip duration remains available separately through its clip data.
     */
    @Nullable PlaybackTimeline getTimeline();
    MinecraftServer getServer();
    ServerLevel getWorld();
    PlaybackPlacement getPlacement();
    Vec3 getRootPosition();
    Optional<Entity> getActor();
    Optional<Entity> getActor(String name);
    Optional<Entity> getAttachmentEntity(String nodeId, String attachmentId);
    Optional<Vec3> getNodeWorldPosition(String nodeId);
    boolean stop();
    boolean stop(PlaybackStopReason reason);

    /**
     * Queues a move to a position in the resolved timeline, including delay, wait and transition segments.
     * Elapsed ticks are preserved. A custom Animation clock requires {@link #seekAnimation(int)} or
     * {@link #seekStep(int, int, int)} when the requested timeline position cannot be reconstructed.
     *
     * @return whether the move was queued; server-synchronized playback cannot be moved
     * @throws IllegalArgumentException if the position is negative, outside the known timeline or cannot be reconstructed
     */
    boolean seekTimeline(int timelineTick);

    /**
     * Queues a move within the active Animation's clip. Timeline position, segment progress and elapsed ticks are
     * preserved; the segment's remaining duration is recalculated from the new clip position when possible.
     *
     * @return whether the move was queued; returns {@code false} outside an Animation segment or when seeking is unavailable
     * @throws IllegalArgumentException if the clip position is outside the Animation's declared duration
     */
    boolean seekAnimation(int animationTick);

    /**
     * Queues a move to a zero-based Sequence step and repetition, at the requested Animation clip position.
     * An {@code emote:empty} step requires an Animation tick of zero. Elapsed ticks are preserved.
     *
     * @return whether the move was queued; returns {@code false} when seeking is unavailable
     * @throws IllegalArgumentException if the step, repetition or clip position does not identify a valid destination
     */
    boolean seekStep(int stepIndex, int repeatIndex, int animationTick);
    boolean setPlacement(PlaybackPlacement placement);
    PlaybackInfo snapshot();
}
