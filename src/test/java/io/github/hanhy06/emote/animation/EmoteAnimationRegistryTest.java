package io.github.hanhy06.emote.animation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmoteAnimationRegistryTest {
    private final EmoteAnimationJsonLoader loader = new EmoteAnimationJsonLoader();

    @Test
    void replacesAndFindsAnimationsOnlyById() throws Exception {
        EmoteAnimation.Loaded zeta = create("zeta:wave");
        EmoteAnimation.Loaded alpha = create("alpha:wave");
        EmoteAnimationRegistry registry = new EmoteAnimationRegistry();

        registry.replace(List.of(zeta, alpha));

        assertEquals(List.of("alpha:wave", "zeta:wave"), registry.getAnimations().stream()
            .map(animation -> animation.animation().id().toString())
            .toList());
        assertSame(alpha, registry.find("alpha:wave"));
        assertNull(registry.find("wave"));
    }

    @Test
    void rejectsDuplicateIds() throws Exception {
        EmoteAnimationRegistry registry = new EmoteAnimationRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.replace(List.of(create("same:id"), create("same:id"))));
    }

    private EmoteAnimation.Loaded create(String id) throws Exception {
        String json = """
            {
              "schema_version": 1,
              "minecraft_version": "26.2",
              "tick_rate": 20,
              "id": "%s",
              "metadata": {"name":"Test","description":"Test","hide_player":false},
              "transform_space": {"coordinate_space":"root_local","matrix_layout":"row_major","matrix_size":16},
              "nodes": {},
              "timeline": {"duration_ticks":1,"loop":"once","loop_delay_ticks":0,"keyframes":[]}
            }
            """.formatted(id);
        return this.loader.parse(Path.of(id.replace(':', '_') + ".json"), json.getBytes(StandardCharsets.UTF_8), "26.2");
    }
}
