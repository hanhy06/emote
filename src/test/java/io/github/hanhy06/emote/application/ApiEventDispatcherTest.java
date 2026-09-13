package io.github.hanhy06.emote.application;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmotePlaybackListener;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.api.PlaybackInfo;
import io.github.hanhy06.emote.api.PlaybackStopReason;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedAnimationFixture;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.runtime.PlaybackNodes;
import io.github.hanhy06.emote.playback.runtime.RootTransform;
import io.github.hanhy06.emote.playback.runtime.SceneRootResolver;
import io.github.hanhy06.emote.playback.session.PlaybackParticipant;
import io.github.hanhy06.emote.playback.session.PlaybackSession;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiEventDispatcherTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stopsStartDispatchWithoutNotifyingLaterListeners() {
        ApiEventDispatcher dispatcher = new ApiEventDispatcher();
        PlaybackParticipant participant = participant();
        PlaybackSession session = session(participant);
        List<String> events = new ArrayList<>();
        dispatcher.addPlaybackListener(new EmotePlaybackListener() {
            @Override
            public void onStarted(PlaybackInfo playback) {
                events.add("first-started");
                dispatcher.onStopped(null, session, participant, PlaybackStopReason.MANUAL);
            }

            @Override
            public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                events.add("first-stopped");
            }
        });
        dispatcher.addPlaybackListener(new EmotePlaybackListener() {
            @Override
            public void onStarted(PlaybackInfo playback) {
                events.add("second-started");
            }

            @Override
            public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                events.add("second-stopped");
            }
        });

        dispatcher.onStarted(null, session, participant);

        assertEquals(List.of("first-started", "first-stopped"), events);
    }

    @Test
    void notifiesEveryListenerWhenStopIsNotReentrant() {
        ApiEventDispatcher dispatcher = new ApiEventDispatcher();
        PlaybackParticipant participant = participant();
        PlaybackSession session = session(participant);
        List<String> events = new ArrayList<>();
        dispatcher.addPlaybackListener(new StopRecordingListener("first", events));
        dispatcher.addPlaybackListener(new StopRecordingListener("second", events));

        dispatcher.onStopped(null, session, participant, PlaybackStopReason.MANUAL);

        assertEquals(List.of("first-stopped", "second-stopped"), events);
    }

    private static PlaybackSession session(PlaybackParticipant participant) {
        PreparedAnimation emote = PreparedAnimationFixture.create("test:api-event", "API Event");
        PlaybackNodes nodes = new PlaybackNodes(
            SceneRootResolver.single(RootTransform.create(Vec3.ZERO, 0.0F)),
            Map.of()
        );
        AnimationPlayer animation = new AnimationPlayer(emote, new EmptyTimelineTarget());
        return new PlaybackSession(
            UUID.randomUUID(),
            Level.OVERWORLD,
            emote.id(),
            emote.id(),
            nodes,
            animation,
            EmotePlayerBehavior.createDefault(),
            participant,
            null
        );
    }

    private static PlaybackParticipant participant() {
        return new PlaybackParticipant(UUID.randomUUID(), ParticipantRole.INITIATOR, Vec3.ZERO, List.of(), false);
    }

    private record StopRecordingListener(String name, List<String> events) implements EmotePlaybackListener {
        @Override
        public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
            this.events.add(this.name + "-stopped");
        }
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
