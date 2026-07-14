package io.github.hanhy06.emote.emote;

import io.github.hanhy06.emote.permission.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.test.EmoteDefinitionFixture.create;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayServiceTest {
    @Test
    void playReturnsSuccess() {
        PlayService service = new PlayService(
            createPlayableEmoteService(),
            (player, definition) -> PlayResult.SUCCESS
        );

        PlayResult result = service.play(null, "wave");

        assertTrue(result.isSuccess());
    }

    @Test
    void playReturnsPlaybackFailure() {
        PlayService service = new PlayService(
            createPlayableEmoteService(),
            (player, definition) -> PlayResult.failure(" Datapack not loaded. ")
        );

        PlayResult result = service.play(null, "wave");

        assertFalse(result.isSuccess());
        assertEquals("Datapack not loaded.", result.errorMessage());
    }

    private PlayableEmoteService createPlayableEmoteService() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(create("wave", "wave", "Wave")));
        return new PlayableEmoteService(registry, new PermissionService() {
            @Override
            public boolean canPlay(net.minecraft.server.level.ServerPlayer player, String namespace) {
                return true;
            }
        });
    }
}
