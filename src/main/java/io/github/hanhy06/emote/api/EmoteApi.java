package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

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

    public abstract boolean stop(ServerPlayer player);

    public abstract EmoteRegistration register(EmoteAnimation animation) throws EmoteAnimationLoadException;

    public abstract Optional<EmoteInfo> find(Identifier emoteId);

    public abstract List<EmoteInfo> getAll();

    public abstract Optional<PlaybackInfo> getPlayback(ServerPlayer player);

    public abstract ListenerRegistration addPlayListener(EmotePlayListener listener);

    public abstract ListenerRegistration addPlaybackListener(EmotePlaybackListener listener);
}
