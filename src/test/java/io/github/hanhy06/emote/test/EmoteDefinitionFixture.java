package io.github.hanhy06.emote.test;

import io.github.hanhy06.emote.emote.EmoteDefinition;

import java.nio.file.Path;
import java.util.List;

public final class EmoteDefinitionFixture {
    private EmoteDefinitionFixture() {
    }

    public static EmoteDefinition create(String namespace, String commandName, String name) {
        return new EmoteDefinition(
                namespace,
                name,
                name + " description",
                commandName,
                "a/default/play_anim_loop",
                true,
                Path.of(namespace + "-pack"),
                1,
                List.of()
        );
    }
}
