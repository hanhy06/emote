package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hanhy06.emote.content.PreparedAnimationFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class EmoteCatalogTest {
    @Test
    void listenerFailureDoesNotInterruptCatalogMutationOrOtherListeners() {
        EmoteCatalog registry = new EmoteCatalog();
        AtomicInteger successfulNotifications = new AtomicInteger();
        registry.addListener(ignored -> {
            throw new IllegalStateException("listener failure");
        });
        registry.addListener(ignored -> successfulNotifications.incrementAndGet());

        registry.register(create("demo:wave", "Wave"));

        assertNotNull(registry.find("demo:wave"));
        assertEquals(2, successfulNotifications.get());
    }

    @Test
    void listenerCanAddAnotherListenerDuringNotification() {
        EmoteCatalog registry = new EmoteCatalog();
        AtomicBoolean added = new AtomicBoolean();
        AtomicInteger nestedNotifications = new AtomicInteger();
        registry.addListener(ignored -> {
            if (added.compareAndSet(false, true)) {
                registry.addListener(nested -> nestedNotifications.incrementAndGet());
            }
        });

        registry.register(create("demo:wave", "Wave"));

        assertEquals(2, nestedNotifications.get());
    }

    @Test
    void reentrantCatalogChangesAreDeliveredInStateOrder() {
        EmoteCatalog registry = new EmoteCatalog();
        AtomicBoolean registeredNestedEmote = new AtomicBoolean();
        List<List<String>> receivedIds = new ArrayList<>();
        registry.addListener(emotes -> {
            if (!emotes.isEmpty() && registeredNestedEmote.compareAndSet(false, true)) {
                registry.register(create("demo:dance", "Dance"));
            }
        });
        registry.addListener(emotes -> receivedIds.add(emotes.stream().map(PlayableEmote::id).toList()));

        registry.register(create("demo:wave", "Wave"));

        assertEquals(List.of(
            List.of(),
            List.of("demo:wave"),
            List.of("demo:dance", "demo:wave")
        ), receivedIds);
    }

    @Test
    void findsDefinitionsByExactAnimationId() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("demo:idle", "Idle")));

        assertNotNull(registry.find("demo:idle"));
        assertNull(registry.find("idle"));
        assertNull(registry.find("DEMO:IDLE"));
    }

    @Test
    void keepsOnlyTheFirst512EmotesById() {
        List<PreparedAnimation> emotes = new ArrayList<>();
        for (int index = 0; index <= EmoteCatalog.MAX_EMOTE_COUNT; index++) {
            String id = "test:%04d".formatted(index);
            emotes.add(create(id, id));
        }
        Collections.reverse(emotes);

        EmoteCatalog registry = new EmoteCatalog();
        int ignoredCount = registry.replace(emotes);

        assertEquals(EmoteCatalog.MAX_EMOTE_COUNT, registry.size());
        assertEquals(1, ignoredCount);
        assertNotNull(registry.find("test:0000"));
        assertNotNull(registry.find("test:0511"));
        assertNull(registry.find("test:0512"));
    }

    @Test
    void keepsApiEmotesAcrossFileReplacement() {
        EmoteCatalog registry = new EmoteCatalog();
        PreparedAnimation apiEmote = create("api:wave", "API Wave");
        registry.register(apiEmote);

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
        EmoteCatalog registry = new EmoteCatalog();
        UUID registrationId = registry.register(create("api:wave", "Wave"));

        assertFalse(registry.unregister("api:wave", UUID.randomUUID()));
        assertNotNull(registry.find("api:wave"));
        assertTrue(registry.unregister("api:wave", registrationId));
        assertNull(registry.find("api:wave"));
    }

    @Test
    void restoresFileEmoteWhenApiCollisionIsRemoved() {
        EmoteCatalog registry = new EmoteCatalog();
        UUID registrationId = registry.register(create("api:wave", "API Wave"));
        registry.replace(List.of(create("api:wave", "File Wave")));

        registry.unregister("api:wave", registrationId);

        assertEquals("File Wave", registry.find("api:wave").name());
        assertEquals(1, registry.fileEmotes().size());
    }

    @Test
    void findsOnlyActiveFileEmotesById() {
        EmoteCatalog registry = new EmoteCatalog();
        PreparedAnimation fileWave = create("demo:wave", "File Wave");
        PreparedAnimation fileDance = create("demo:dance", "File Dance");
        UUID registrationId = registry.register(create("demo:wave", "API Wave"));

        registry.replace(List.of(fileWave, fileDance));

        assertNull(registry.findFileEmote("demo:wave"));
        assertSame(fileDance, registry.findFileEmote("demo:dance"));

        registry.unregister("demo:wave", registrationId);

        assertSame(fileWave, registry.findFileEmote("demo:wave"));
    }

    @Test
    void apiRegistrationTemporarilyTakesARegistrySlotFromFileEmotes() {
        List<PreparedAnimation> fileEmotes = new ArrayList<>();
        for (int index = 0; index < EmoteCatalog.MAX_EMOTE_COUNT; index++) {
            String id = "file:%04d".formatted(index);
            fileEmotes.add(create(id, id));
        }
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(fileEmotes);

        UUID registrationId = registry.register(create("api:wave", "Wave"));

        assertEquals(EmoteCatalog.MAX_EMOTE_COUNT, registry.size());
        assertNotNull(registry.find("api:wave"));
        assertNull(registry.find("file:0511"));

        registry.unregister("api:wave", registrationId);

        assertNotNull(registry.find("file:0511"));
    }

    @Test
    void clearingApiRegistrationsKeepsFileEmotesAndInvalidatesRegistrationIds() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("file:dance", "Dance")));
        UUID firstRegistrationId = registry.register(create("api:wave", "Wave"));
        UUID secondRegistrationId = registry.register(create("api:clap", "Clap"));

        int removedCount = registry.clearApiRegistrations();

        assertEquals(2, removedCount);
        assertFalse(registry.isApiRegistrationActive("api:wave", firstRegistrationId));
        assertFalse(registry.isApiRegistrationActive("api:clap", secondRegistrationId));
        assertNull(registry.find("api:wave"));
        assertNull(registry.find("api:clap"));
        assertNotNull(registry.find("file:dance"));
        assertEquals(1, registry.size());
    }

    @Test
    void includesResolvedSequencesInDefinitionLookup() {
        PreparedAnimation animation = create("demo:sit_down", "Sit Down");
        EmoteSequence source = new EmoteSequence(
            Path.of("sit.json"),
            Identifier.parse("demo:sit"),
            new EmoteMetadata("Sit", "Sit sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(Identifier.parse(animation.id()), 1))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, java.util.Map.of(animation.id(), animation));
        EmoteCatalog registry = new EmoteCatalog();

        registry.replace(List.of(animation, sequence));

        assertSame(sequence, registry.find("demo:sit"));
        assertEquals(List.of("demo:sit", "demo:sit_down"), registry.emotes().stream()
            .map(PlayableEmote::id)
            .toList());
        assertEquals(List.of(animation), registry.animations());
    }

    @Test
    void restoresFileSequenceWhenApiCollisionIsRemoved() {
        PreparedAnimation animation = create("demo:offer", "Offer");
        EmoteSequence source = new EmoteSequence(
            Path.of("pair.json"),
            Identifier.parse("demo:pair"),
            new EmoteMetadata("Pair", "Pair sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(Identifier.parse(animation.id()), 1))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, java.util.Map.of(animation.id(), animation));
        EmoteCatalog registry = new EmoteCatalog();
        UUID registrationId = registry.register(create("demo:pair", "API Pair"));

        registry.replace(List.of(animation, sequence));
        registry.unregister("demo:pair", registrationId);

        assertSame(sequence, registry.find("demo:pair"));
    }

    @Test
    void appliesTheRegistryLimitAcrossAnimationsAndSequencesById() {
        PreparedAnimation animation = create("test:animation", "Animation");
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("test:0000"),
            new EmoteMetadata("Sequence", "Sequence"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()),
            List.of(new EmoteSequence.EmoteStep(Identifier.parse(animation.id()), 1))
        );
        PreparedSequence sequence = PreparedSequence.resolve(source, java.util.Map.of(animation.id(), animation));
        List<PlayableEmote> definitions = new ArrayList<>();
        definitions.add(animation);
        definitions.add(sequence);
        for (int index = 1; index < EmoteCatalog.MAX_EMOTE_COUNT; index++) {
            String id = "test:%04d".formatted(index);
            definitions.add(create(id, id));
        }

        EmoteCatalog registry = new EmoteCatalog();
        int ignoredCount = registry.replace(definitions);

        assertEquals(1, ignoredCount);
        assertSame(sequence, registry.find("test:0000"));
        assertNull(registry.find("test:animation"));
    }
}
