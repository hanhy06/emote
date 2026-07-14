package io.github.hanhy06.emote.playback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackInterruptionCallback {
    Event<PlaybackInterruptionCallback> EVENT = EventFactory.createArrayBacked(
        PlaybackInterruptionCallback.class,
        callbacks -> player -> {
            for (PlaybackInterruptionCallback callback : callbacks) {
                callback.interruptPlayback(player);
            }
        }
    );

    void interruptPlayback(ServerPlayer player);
}
