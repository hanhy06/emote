package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hanhy06.emote.test.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmoteRegistryTest {
    @Test
    void findsDefinitionsByExactAnimationId() {
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(List.of(create("demo:idle", "Idle")));

        assertNotNull(registry.find("demo:idle"));
        assertNull(registry.find("idle"));
        assertNull(registry.find("DEMO:IDLE"));
    }
}
