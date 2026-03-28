package io.github.hanhy06.emote.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

@Environment(EnvType.CLIENT)
public class EmotePerspectiveController {
	private CameraType previousCameraType = CameraType.FIRST_PERSON;
	private boolean restoreCameraOnStop;

	public void clear() {
		this.previousCameraType = CameraType.FIRST_PERSON;
		this.restoreCameraOnStop = false;
	}

	public void handlePlaybackState(boolean active) {
		if (active) {
			switchToThirdPersonIfNeeded();
			return;
		}

		restorePerspectiveIfNeeded();
	}

	private void switchToThirdPersonIfNeeded() {
		Minecraft client = Minecraft.getInstance();
		CameraType currentCameraType = client.options.getCameraType();
		if (!currentCameraType.isFirstPerson()) {
			this.restoreCameraOnStop = false;
			return;
		}

		this.previousCameraType = currentCameraType;
		this.restoreCameraOnStop = true;
		client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	private void restorePerspectiveIfNeeded() {
		if (!this.restoreCameraOnStop) {
			return;
		}

		Minecraft.getInstance().options.setCameraType(this.previousCameraType);
		this.restoreCameraOnStop = false;
	}
}
