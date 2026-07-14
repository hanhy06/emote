package io.github.hanhy06.emote.playback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

public interface PlaybackVisibilityCallback {
    Event<PlaybackVisibilityCallback> EVENT = EventFactory.createArrayBacked(
        PlaybackVisibilityCallback.class,
        callbacks -> player -> {
            for (PlaybackVisibilityCallback callback : callbacks) {
                callback.afterInvisibilityUpdate(player);
            }
        }
    );

    void afterInvisibilityUpdate(ServerPlayer player);
}
