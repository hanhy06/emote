package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.test.EmoteDefinitionFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class EmoteRegistryTest {
    @Test
    void findsDefinitionsByExactAnimationId() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replaceDefinitions(List.of(create("demo:idle", "Idle")));

        assertNotNull(registry.findDefinition("demo:idle"));
        assertNull(registry.findDefinition("idle"));
        assertNull(registry.findDefinition("DEMO:IDLE"));
    }
}
