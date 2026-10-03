package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.application.EmoteSummary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelController {
    private static final String MENU_FALLBACK_COMMAND = "emote";

    private final WheelShortcutSettings shortcutSettings;
    private boolean syncedFromServer;
    private String lastSelectedId = "";

    public WheelController(WheelShortcutSettings shortcutSettings) {
        this.shortcutSettings = shortcutSettings;
    }

    public void clear() {
        this.shortcutSettings.clearSession();
        this.syncedFromServer = false;
        this.lastSelectedId = "";
    }

    public void updateEntries(List<EmoteSummary> emotes) {
        this.shortcutSettings.updateServer(findServerKey(Minecraft.getInstance()), emotes);
        this.syncedFromServer = true;
    }

    public void registerBinding(KeyMapping keyMapping) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickBinding(client, keyMapping));
    }

    private void openWheel(Minecraft client, KeyMapping keyMapping) {
        if (!this.syncedFromServer) {
            client.player.connection.sendUnattendedCommand(MENU_FALLBACK_COMMAND, null);
            return;
        }

        client.setScreen(new WheelScreen(this, this.shortcutSettings.selectedEmotes(), findInitialPageIndex(), keyMapping));
    }

    public void play(EmoteSummary emoteSummary) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        this.lastSelectedId = emoteSummary.id();
        player.connection.sendUnattendedCommand(emoteSummary.createPlayCommand(), null);
    }

    public void openShortcutEditor() {
        Minecraft.getInstance().setScreen(new WheelShortcutScreen(this));
    }

    public WheelShortcutSettings shortcuts() {
        return this.shortcutSettings;
    }

    private void tickBinding(Minecraft client, KeyMapping keyMapping) {
        if (client.screen != null || client.player == null) {
            return;
        }

        if (keyMapping.consumeClick()) {
            openWheel(client, keyMapping);
        }
    }

    private int findInitialPageIndex() {
        List<EmoteSummary> shortcuts = this.shortcutSettings.selectedEmotes();
        if (this.lastSelectedId.isEmpty() || shortcuts.isEmpty()) {
            return 0;
        }

        for (int index = 0; index < shortcuts.size(); index++) {
            if (shortcuts.get(index).id().equals(this.lastSelectedId)) {
                return index / WheelGeometry.SLOT_COUNT;
            }
        }

        return 0;
    }

    private static String findServerKey(Minecraft client) {
        if (client.getSingleplayerServer() != null) {
            return "singleplayer:" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }

        ServerData serverData = client.getCurrentServer();
        return serverData == null ? "unknown" : "server:" + serverData.ip;
    }
}
