package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.hanhy06.emote.test.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void keepsOnlyTheFirst512EmotesById() {
        List<RegisteredEmote> emotes = new ArrayList<>();
        for (int index = 0; index <= EmoteRegistry.MAX_EMOTE_COUNT; index++) {
            String id = "test:%04d".formatted(index);
            emotes.add(create(id, id));
        }
        Collections.reverse(emotes);

        EmoteRegistry registry = new EmoteRegistry();
        int ignoredCount = registry.replace(emotes);

        assertEquals(EmoteRegistry.MAX_EMOTE_COUNT, registry.size());
        assertEquals(1, ignoredCount);
        assertNotNull(registry.find("test:0000"));
        assertNotNull(registry.find("test:0511"));
        assertNull(registry.find("test:0512"));
    }
}
