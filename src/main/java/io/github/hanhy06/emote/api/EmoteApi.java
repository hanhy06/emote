package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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

    public abstract PlayResult play(ServerPlayer player, Identifier emoteId, PlaybackPlacement placement);

    public abstract PlayResult play(ServerLevel world, Identifier emoteId, Vec3 position, float yaw);

    public abstract boolean stop(ServerPlayer player);

    public abstract Registration register(EmoteAnimation animation) throws EmoteLoadException;

    public abstract Registration register(EmoteSequence sequence) throws EmoteLoadException;

    public abstract Optional<EmoteInfo> get(Identifier emoteId);

    public abstract List<EmoteInfo> getAll();

    public abstract Optional<Playback> getPlayback(ServerPlayer player);

    public abstract Optional<Playback> getPlayback(UUID sessionId);

    public abstract Registration registerCallbacks(Identifier name, EmoteCallbacks callbacks);

    public abstract ListenerRegistration addPlayListener(EmotePlayListener listener);

    public abstract ListenerRegistration addPlaybackListener(EmotePlaybackListener listener);

}
