package io.github.hanhy06.emote.api;

import io.github.hanhy06.emote.animation.EmoteAnimation;
import io.github.hanhy06.emote.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.emote.PlayResult;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

public abstract class EmoteApi {
    private static volatile EmoteApi instance;

    protected EmoteApi() {
        synchronized (EmoteApi.class) {
            if (instance != null) {
                throw new IllegalStateException("The emote API is already initialized.");
            }
            instance = this;
        }
    }

    public static EmoteApi getInstance() {
        EmoteApi currentInstance = instance;
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
