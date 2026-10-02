package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public abstract class EmoteApi {
    public static volatile EmoteApi INSTANCE;

    protected EmoteApi() {
        synchronized (EmoteApi.class) {
            if (INSTANCE != null) {
                throw new IllegalStateException("The emote API is already initialized.");
            }
            INSTANCE = this;
        }
    }

    public static EmoteApi getInstance() {
        EmoteApi currentInstance = INSTANCE;
        if (currentInstance == null) {
            throw new IllegalStateException("The emote API is not initialized.");
        }
        return currentInstance;
    }

    public abstract PlayResult play(ServerPlayer player, Identifier emoteId);

    public abstract PlayResult play(ServerPlayer player, Identifier emoteId, PlayOptions options);

    public abstract boolean stop(ServerPlayer player);

    /** Stops only the specified active player session, never a newer playback of that actor. */
    public abstract boolean stop(UUID sessionId);

    /** Applies scene placement immediately. Does not move the actor or change stop conditions. */
    public abstract boolean setPlacement(UUID sessionId, PlaybackPlacement placement);

    /** Empty for an inactive player session or an unknown node. Server-thread only. */
    public abstract Optional<Vec3> getNodeWorldPosition(UUID sessionId, String nodeId);

    public abstract Registration register(EmoteAnimation animation) throws EmoteAnimationLoadException;

    public abstract Optional<EmoteInfo> find(Identifier emoteId);

    public abstract List<EmoteInfo> getAll();

    public abstract Optional<PlaybackInfo> getPlayback(ServerPlayer player);

    public abstract Optional<PlaybackInfo> getPlayback(UUID sessionId);

    /** Returns the selected timeline of an active player playback. Call on the server thread. */
    public abstract Optional<PlaybackTimeline> getTimeline(UUID sessionId);

    public abstract Registration registerCallbacks(Identifier name, EmoteCallbacks callbacks);

    public abstract ListenerRegistration addPlayListener(EmotePlayListener listener);

    public abstract ListenerRegistration addPlaybackListener(EmotePlaybackListener listener);

}
