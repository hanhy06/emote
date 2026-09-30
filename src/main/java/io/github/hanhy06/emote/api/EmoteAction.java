package io.github.hanhy06.emote.api;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface EmoteAction {
    void run(PlaybackContext context, JsonObject parameters);
}
