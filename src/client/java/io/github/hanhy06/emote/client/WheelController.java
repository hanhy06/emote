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
        Minecraft client = Minecraft.getInstance();
        int previousSlotCount = this.shortcutSettings.slotIds().size();
        this.shortcutSettings.updateServer(findServerKey(client), emotes);
        this.syncedFromServer = true;
        if (client.gui.screen() instanceof WheelShortcutScreen editor) {
            editor.refreshCatalog(previousSlotCount);
        }
    }

    public void registerBinding(KeyMapping keyMapping) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickBinding(client, keyMapping));
    }

    private void openWheel(Minecraft client, KeyMapping keyMapping) {
        if (!this.syncedFromServer) {
            client.player.connection.sendUnattendedCommand(MENU_FALLBACK_COMMAND, null);
            return;
        }

        client.gui.setScreen(new WheelScreen(this, this.shortcutSettings.selectedSlots(), findInitialPageIndex(), keyMapping));
    }

    public void play(EmoteSummary emoteSummary) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        this.lastSelectedId = emoteSummary.id();
        player.connection.sendUnattendedCommand(emoteSummary.createPlayCommand(), null);
    }

    void openShortcutEditor(int pageIndex) {
        Minecraft.getInstance().gui.setScreen(new WheelShortcutScreen(this.shortcutSettings, pageIndex));
    }

    void openSearch(KeyMapping keyMapping) {
        Minecraft.getInstance().gui.setScreen(new WheelSearchScreen(this, this.shortcutSettings.allEmotes(), keyMapping));
    }

    private void tickBinding(Minecraft client, KeyMapping keyMapping) {
        if (client.gui.screen() != null || client.player == null) {
            return;
        }

        if (keyMapping.consumeClick()) {
            openWheel(client, keyMapping);
        }
    }

    private int findInitialPageIndex() {
        if (this.shortcutSettings.emote(this.lastSelectedId) == null) {
            return 0;
        }

        int slot = this.shortcutSettings.slotIds().indexOf(this.lastSelectedId);
        return Math.max(0, slot) / WheelGeometry.SLOT_COUNT;
    }

    private static String findServerKey(Minecraft client) {
        if (client.getSingleplayerServer() != null) {
            return "singleplayer:" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }

        ServerData serverData = client.getCurrentServer();
        return serverData == null ? "unknown" : "server:" + serverData.ip;
    }
}
