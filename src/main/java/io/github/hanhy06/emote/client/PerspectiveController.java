package io.github.hanhy06.emote.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class PerspectiveController {
    private final Supplier<CameraType> cameraTypeSupplier;
    private final Consumer<CameraType> cameraTypeSetter;
    private CameraType previousCameraType = CameraType.FIRST_PERSON;
    private boolean restoreCameraOnStop;
    private boolean playbackActive;
    private boolean hideLocalPlayerEquipment;

    public PerspectiveController() {
        this(
            () -> Minecraft.getInstance().options.getCameraType(),
            cameraType -> Minecraft.getInstance().options.setCameraType(cameraType)
        );
    }

    PerspectiveController(Supplier<CameraType> cameraTypeSupplier, Consumer<CameraType> cameraTypeSetter) {
        this.cameraTypeSupplier = Objects.requireNonNull(cameraTypeSupplier, "cameraTypeSupplier");
        this.cameraTypeSetter = Objects.requireNonNull(cameraTypeSetter, "cameraTypeSetter");
    }

    public void clear() {
        restorePerspectiveIfNeeded();
        this.previousCameraType = CameraType.FIRST_PERSON;
        this.restoreCameraOnStop = false;
        this.playbackActive = false;
        this.hideLocalPlayerEquipment = false;
    }

    public void handlePlaybackState(boolean active, boolean hidePlayer) {
        this.playbackActive = active;
        this.hideLocalPlayerEquipment = active && hidePlayer;
        if (active) {
            switchToThirdPersonIfNeeded();
            return;
        }

        restorePerspectiveIfNeeded();
    }

    public boolean isPlaybackActive() {
        return this.playbackActive;
    }

    public boolean shouldHideLocalPlayerEquipment() {
        return this.hideLocalPlayerEquipment;
    }

    private void switchToThirdPersonIfNeeded() {
        CameraType currentCameraType = this.cameraTypeSupplier.get();
        if (!currentCameraType.isFirstPerson()) {
            this.restoreCameraOnStop = false;
            return;
        }

        this.previousCameraType = currentCameraType;
        this.restoreCameraOnStop = true;
        this.cameraTypeSetter.accept(CameraType.THIRD_PERSON_FRONT);
    }

    private void restorePerspectiveIfNeeded() {
        if (!this.restoreCameraOnStop) {
            return;
        }

        this.cameraTypeSetter.accept(this.previousCameraType);
        this.restoreCameraOnStop = false;
    }
}
