package io.github.hanhy06.emote.playback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackMountCallback {
    Event<PlaybackMountCallback> EVENT = EventFactory.createArrayBacked(
        PlaybackMountCallback.class,
        callbacks -> player -> {
            for (PlaybackMountCallback callback : callbacks) {
                callback.afterMount(player);
            }
        }
    );

    void afterMount(ServerPlayer player);
}
