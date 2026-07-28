package io.github.hanhy06.emote.api;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class EmotePlayEvent {
    private final ServerPlayer player;
    private final EmoteInfo emote;
    private final PlaySource source;
    private Component cancellationMessage;

    public EmotePlayEvent(ServerPlayer player, EmoteInfo emote, PlaySource source) {
        this.player = Objects.requireNonNull(player, "player");
        this.emote = Objects.requireNonNull(emote, "emote");
        this.source = Objects.requireNonNull(source, "source");
    }

    public ServerPlayer player() {
        return this.player;
    }

    public EmoteInfo emote() {
        return this.emote;
    }

    public PlaySource source() {
        return this.source;
    }

    public void cancel(Component message) {
        this.cancellationMessage = Objects.requireNonNull(message, "message");
    }

    public boolean isCancelled() {
        return this.cancellationMessage != null;
    }

    public Component cancellationMessage() {
        if (this.cancellationMessage == null) {
            throw new IllegalStateException("The play event is not cancelled.");
        }
        return this.cancellationMessage;
    }
}
