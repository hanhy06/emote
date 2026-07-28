package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.github.hanhy06.emote.emote.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void keepsApiEmotesAcrossFileReplacement() {
        EmoteRegistry registry = new EmoteRegistry();
        RegisteredEmote apiEmote = create("api:wave", "API Wave");
        registry.registerApi(apiEmote);

        int ignoredCount = registry.replace(List.of(
            create("api:wave", "File Wave"),
            create("file:dance", "Dance")
        ));

        assertEquals(1, ignoredCount);
        assertSame(apiEmote, registry.find("api:wave"));
        assertNotNull(registry.find("file:dance"));
    }

    @Test
    void onlyMatchingRegistrationCanRemoveApiEmote() {
        EmoteRegistry registry = new EmoteRegistry();
        UUID registrationId = registry.registerApi(create("api:wave", "Wave"));

        assertFalse(registry.unregisterApi("api:wave", UUID.randomUUID()));
        assertNotNull(registry.find("api:wave"));
        assertTrue(registry.unregisterApi("api:wave", registrationId));
        assertNull(registry.find("api:wave"));
    }

    @Test
    void restoresFileEmoteWhenApiCollisionIsRemoved() {
        EmoteRegistry registry = new EmoteRegistry();
        UUID registrationId = registry.registerApi(create("api:wave", "API Wave"));
        registry.replace(List.of(create("api:wave", "File Wave")));

        registry.unregisterApi("api:wave", registrationId);

        assertEquals("File Wave", registry.find("api:wave").name());
        assertEquals(1, registry.getFileEmotes().size());
    }

    @Test
    void apiRegistrationTemporarilyTakesARegistrySlotFromFileEmotes() {
        List<RegisteredEmote> fileEmotes = new ArrayList<>();
        for (int index = 0; index < EmoteRegistry.MAX_EMOTE_COUNT; index++) {
            String id = "file:%04d".formatted(index);
            fileEmotes.add(create(id, id));
        }
        EmoteRegistry registry = new EmoteRegistry();
        registry.replace(fileEmotes);

        UUID registrationId = registry.registerApi(create("api:wave", "Wave"));

        assertEquals(EmoteRegistry.MAX_EMOTE_COUNT, registry.size());
        assertNotNull(registry.find("api:wave"));
        assertNull(registry.find("file:0511"));

        registry.unregisterApi("api:wave", registrationId);

        assertNotNull(registry.find("file:0511"));
    }
}
