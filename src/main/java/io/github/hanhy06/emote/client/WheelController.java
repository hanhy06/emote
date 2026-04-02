package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.emote.PlayableEmote;
import io.github.hanhy06.emote.network.payload.EmoteWheelPlayPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

@Environment(EnvType.CLIENT)
public class WheelController {
    private static final String MENU_FALLBACK_COMMAND = "emote menu";
    private List<PlayableEmote> syncedEmotes = List.of();
    private boolean syncedFromServer;
    private String lastSelectionKey = "";
    private boolean bindingReleaseArmed;
    private boolean holdWasDown;

    public void clear() {
        this.syncedEmotes = List.of();
        this.syncedFromServer = false;
        this.lastSelectionKey = "";
        this.bindingReleaseArmed = false;
        this.holdWasDown = false;
    }

    public void updateEmotes(List<PlayableEmote> emotes) {
        this.syncedEmotes = List.copyOf(emotes);
        this.syncedFromServer = true;
    }

    public void registerBinding(KeyMapping keyMapping) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickBinding(client, keyMapping));
    }

    public void openWheel(Component bindingLabel) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) {
            return;
        }

        if (!this.syncedFromServer) {
            openMenuFallback(client.player);
            return;
        }

        client.setScreen(new WheelScreen(this, this.syncedEmotes, findInitialPageIndex(), bindingLabel));
    }

    public void playEmote(PlayableEmote playableEmote) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        this.lastSelectionKey = playableEmote.selectionKey();
        if (ClientPlayNetworking.canSend(EmoteWheelPlayPayload.TYPE)) {
            ClientPlayNetworking.send(new EmoteWheelPlayPayload(playableEmote.commandName(), playableEmote.animationName()));
            return;
        }

        player.connection.sendUnattendedCommand(playableEmote.createPlayCommand(), null);
    }

    private void openMenuFallback(LocalPlayer player) {
        player.connection.sendUnattendedCommand(MENU_FALLBACK_COMMAND, null);
    }

    private void tickBinding(Minecraft client, KeyMapping keyMapping) {
        boolean holdDown = isBindingDown(client, keyMapping);

        if (client.screen instanceof WheelScreen wheelScreen) {
            if (holdDown) {
                this.bindingReleaseArmed = true;
            } else if (this.bindingReleaseArmed) {
                this.bindingReleaseArmed = false;
                wheelScreen.handleBindingReleased();
            }
            this.holdWasDown = holdDown;
            return;
        }

        this.bindingReleaseArmed = false;
        if (client.screen != null || client.player == null) {
            this.holdWasDown = holdDown;
            return;
        }

        if (holdDown && !this.holdWasDown) {
            if (keyMapping.same(client.options.keyPickItem)) {
                drainClicks(client.options.keyPickItem);
            }
            openWheel(keyMapping.getTranslatedKeyMessage());
            this.bindingReleaseArmed = true;
        }

        this.holdWasDown = holdDown;
    }

    private int findInitialPageIndex() {
        if (this.lastSelectionKey.isEmpty() || this.syncedEmotes.isEmpty()) {
            return 0;
        }

        for (int index = 0; index < this.syncedEmotes.size(); index++) {
            if (this.syncedEmotes.get(index).selectionKey().equals(this.lastSelectionKey)) {
                return index / WheelScreen.SLOT_COUNT;
            }
        }

        return 0;
    }

    private static void drainClicks(KeyMapping keyMapping) {
        boolean hasQueuedClick = keyMapping.consumeClick();
        while (hasQueuedClick) {
            hasQueuedClick = keyMapping.consumeClick();
        }
    }

    private static boolean isBindingDown(Minecraft client, KeyMapping keyMapping) {
        InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(keyMapping);
        return switch (boundKey.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
            case MOUSE -> isMouseButtonDown(client, boundKey.getValue());
            case SCANCODE -> keyMapping.isDown();
        };
    }

    private static boolean isMouseButtonDown(Minecraft client, int button) {
        return GLFW.glfwGetMouseButton(client.getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }
}
