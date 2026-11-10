package io.github.hanhy06.emote.api;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public interface PlaybackContext extends Playback {
    String getPayload();
    Identifier getScopeEmoteId();
    long getScopeElapsedTicks();
    Optional<PlaybackStopReason> getCloseReason();
    @Nullable Object getUserState();
    void setUserState(@Nullable Object state);
}
