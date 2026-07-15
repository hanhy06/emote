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
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertTrue(result.isSuccess());
    }

    @Test
    void playReturnsPlaybackFailure() {
        PlayService service = new PlayService(
            createPlayableEmoteService(),
            (ignoredPlayer, ignoredDefinition) -> PlayResult.failure(" Animation unavailable. ")
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertFalse(result.isSuccess());
        assertEquals("Animation unavailable.", result.errorMessage());
    }

    private PlayableEmoteService createPlayableEmoteService() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(create("wave", "Wave")));
        return new PlayableEmoteService(registry, new PermissionService() {
            @Override
            public boolean canPlay(net.minecraft.server.level.ServerPlayer player, String id) {
                return true;
            }
        });
    }
}
