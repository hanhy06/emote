package io.github.hanhy06.emote.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class PerspectiveController {
    public static PerspectiveController INSTANCE;

    private boolean restoreCameraOnStop;
    private boolean hideLocalPlayerEquipment;

    public PerspectiveController() {
        INSTANCE = this;
    }

    public void clear() {
        restorePerspectiveIfNeeded();
        this.hideLocalPlayerEquipment = false;
    }

    public void handlePlaybackState(boolean active, boolean hidePlayer) {
        this.hideLocalPlayerEquipment = active && hidePlayer;
        if (active) {
            switchToThirdPersonIfNeeded();
            return;
        }

        restorePerspectiveIfNeeded();
    }

    public boolean shouldHideLocalPlayerEquipment() {
        return this.hideLocalPlayerEquipment;
    }

    private void switchToThirdPersonIfNeeded() {
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            this.restoreCameraOnStop = false;
            return;
        }

        this.restoreCameraOnStop = true;
        Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
    }

    private void restorePerspectiveIfNeeded() {
        if (!this.restoreCameraOnStop) {
            return;
        }

        Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        this.restoreCameraOnStop = false;
    }
}
