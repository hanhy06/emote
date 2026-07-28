package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.emote.PlayableEmote;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelController {
    private static final String MENU_FALLBACK_COMMAND = "emote";
    private List<PlayableEmote> syncedEmotes = List.of();
    private boolean syncedFromServer;
    private String lastSelectedId = "";

    public void clear() {
        this.syncedEmotes = List.of();
        this.syncedFromServer = false;
        this.lastSelectedId = "";
    }

    public void updateEmotes(List<PlayableEmote> emotes) {
        this.syncedEmotes = List.copyOf(emotes);
        this.syncedFromServer = true;
    }

    public void registerBinding(KeyMapping keyMapping) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickBinding(client, keyMapping));
    }

    private void openWheel(KeyMapping keyMapping) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() != null) {
            return;
        }

        if (!this.syncedFromServer) {
            client.player.connection.sendUnattendedCommand(MENU_FALLBACK_COMMAND, null);
            return;
        }

        client.gui.setScreen(new WheelScreen(this, this.syncedEmotes, findInitialPageIndex(), keyMapping));
    }

    public void playEmote(PlayableEmote playableEmote) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        this.lastSelectedId = playableEmote.id();
        player.connection.sendUnattendedCommand(playableEmote.createPlayCommand(), null);
    }

    private void tickBinding(Minecraft client, KeyMapping keyMapping) {
        if (client.gui.screen() != null || client.player == null) {
            return;
        }

        if (keyMapping.consumeClick()) {
            openWheel(keyMapping);
        }
    }

    private int findInitialPageIndex() {
        if (this.lastSelectedId.isEmpty() || this.syncedEmotes.isEmpty()) {
            return 0;
        }

        for (int index = 0; index < this.syncedEmotes.size(); index++) {
            if (this.syncedEmotes.get(index).id().equals(this.lastSelectedId)) {
                return index / WheelGeometry.SLOT_COUNT;
            }
        }

        return 0;
    }
}
