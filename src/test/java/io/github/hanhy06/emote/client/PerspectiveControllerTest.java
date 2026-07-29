package io.github.hanhy06.emote.client;

import net.minecraft.client.CameraType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PerspectiveControllerTest {
    @Test
    void clearRestoresPerspectiveAfterPlaybackConnectionEnds() {
        AtomicReference<CameraType> cameraType = new AtomicReference<>(CameraType.FIRST_PERSON);
        PerspectiveController controller = new PerspectiveController(cameraType::get, cameraType::set);

        controller.handlePlaybackState(true);

        assertEquals(CameraType.THIRD_PERSON_FRONT, cameraType.get());
        assertTrue(controller.isPlaybackActive());

        controller.clear();

        assertEquals(CameraType.FIRST_PERSON, cameraType.get());
        assertFalse(controller.isPlaybackActive());
    }
}
