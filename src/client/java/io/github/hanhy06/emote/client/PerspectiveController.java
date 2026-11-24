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

    public void handlePlaybackState(boolean active, boolean hidePlayer, boolean autoThirdPerson) {
        this.hideLocalPlayerEquipment = active && hidePlayer;
        if (active && autoThirdPerson) {
            switchToThirdPersonIfNeeded();
        } else {
            restorePerspectiveIfNeeded();
        }
    }

    public boolean shouldHideLocalPlayerEquipment() {
        return this.hideLocalPlayerEquipment;
    }

    private void switchToThirdPersonIfNeeded() {
        var options = Minecraft.getInstance().options;
        if (options.getCameraType().isFirstPerson()) {
            options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            this.restoreCameraOnStop = true;
        }
    }

    private void restorePerspectiveIfNeeded() {
        if (!this.restoreCameraOnStop) {
            return;
        }

        this.restoreCameraOnStop = false;
        var options = Minecraft.getInstance().options;
        if (options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
            options.setCameraType(CameraType.FIRST_PERSON);
        }
    }
}
