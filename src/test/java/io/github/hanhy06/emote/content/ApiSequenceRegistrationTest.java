package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteInfo;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.application.ApiEventDispatcher;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ApiSequenceRegistrationTest {
    @Test
    void registersPublicSequenceAndSeparatesDefinitionFromChosenPlayback() {
        PreparedAnimation first = PreparedAnimationFixture.create("test:first", "First");
        PreparedAnimation second = PreparedAnimationFixture.create("test:second", "Second");
        EmoteSequence source = sequence(new EmoteSequence.EmoteStep(List.of(first.animation().id(), second.animation().id()), 2));
        PreparedSequence prepared = PreparedSequence.resolve(source, Map.of(first.id(), first, second.id(), second));
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.register(first);
        catalog.register(second);
        var registrationId = catalog.register(prepared);
        assertTrue(catalog.isApiRegistrationActive(prepared.id(), registrationId));
        assertEquals(2, catalog.animations().size());
        assertEquals(3, catalog.emotes().size());
        EmoteInfo info = ApiEventDispatcher.toInfo(catalog.find(prepared.id()));
        assertEquals(EmoteInfo.Kind.SEQUENCE, info.kind());
        assertNull(info.durationTicks());
        assertEquals(EmoteInfo.Kind.ANIMATION, ApiEventDispatcher.toInfo(first).kind());
        assertEquals(first.durationTicks(), ApiEventDispatcher.toInfo(first).durationTicks());
        PreparedAnimation playback = ((PreparedSequence) catalog.find(prepared.id())).compile(new Random(0));
        assertEquals(playback.durationTicks(), playback.playbackTimeline().durationTicks());
        assertEquals(2, playback.playbackSegments().size());
        assertThrows(UnsupportedOperationException.class, () -> source.steps().clear());
        assertTrue(catalog.unregister(prepared.id(), registrationId));
        assertFalse(catalog.isApiRegistrationActive(prepared.id(), registrationId));
        assertNull(catalog.find(prepared.id()));
        assertSame(first, catalog.find(first.id()));
    }

    @Test
    void reportsFixedSequenceDurationIncludingWaitAndTransition() {
        PreparedAnimation animation = PreparedAnimationFixture.create("test:wave", "Wave");
        PreparedSequence prepared = PreparedSequence.resolve(sequence(
            new EmoteSequence.EmoteStep(animation.animation().id(), 1),
            new EmoteSequence.WaitStep(3),
            new EmoteSequence.EmoteStep(animation.animation().id(), 1, 4)), Map.of(animation.id(), animation));
        assertEquals(animation.durationTicks() * 2 + 7, ApiEventDispatcher.toInfo(prepared).durationTicks());
    }

    @Test
    void rejectsMissingReferencesAndSequenceReferencesBeforeRegistration() {
        EmoteSequence source = sequence(new EmoteSequence.EmoteStep(Identifier.parse("test:missing"), 1));
        assertThrows(IllegalArgumentException.class, () -> PreparedSequence.resolve(source, Map.of()));
        PreparedAnimation animation = PreparedAnimationFixture.create("test:wave", "Wave");
        PreparedSequence prepared = PreparedSequence.resolve(sequence(new EmoteSequence.EmoteStep(animation.animation().id(), 1)),
            Map.of(animation.id(), animation));
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.register(animation);
        catalog.register(prepared);
        var animations = catalog.animations().stream().collect(java.util.stream.Collectors.toMap(PreparedAnimation::id, item -> item));
        assertThrows(IllegalArgumentException.class, () -> PreparedSequence.resolve(
            sequence(new EmoteSequence.EmoteStep(Identifier.parse(prepared.id()), 1)), animations));
    }

    @Test
    void unregisteringDependencyInvalidatesAllReferencingApiSequences() {
        PreparedAnimation animation = PreparedAnimationFixture.create("test:wave", "Wave");
        EmoteCatalog catalog = new EmoteCatalog();
        var animationRegistration = catalog.register(animation);
        PreparedSequence prepared = PreparedSequence.resolve(sequence(new EmoteSequence.EmoteStep(animation.animation().id(), 1)),
            Map.of(animation.id(), animation));
        var sequenceRegistration = catalog.register(prepared);
        assertTrue(catalog.unregister(animation.id(), animationRegistration));
        assertNull(catalog.find(prepared.id()));
        assertFalse(catalog.isApiRegistrationActive(prepared.id(), sequenceRegistration));
        assertFalse(catalog.unregister(prepared.id(), sequenceRegistration));
        assertEquals(0, catalog.size());
    }

    @Test
    void fileReloadRebindsApiSequenceAndRemovalInvalidatesIt() {
        PreparedAnimation first = PreparedAnimationFixture.create("test:wave", "First");
        PreparedAnimation replacement = PreparedAnimationFixture.create("test:wave", "Replacement");
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.replace(List.of(first));
        PreparedSequence prepared = PreparedSequence.resolve(sequence(new EmoteSequence.EmoteStep(first.animation().id(), 1)),
            Map.of(first.id(), first));
        var registration = catalog.register(prepared);
        catalog.replace(List.of(replacement));
        PreparedSequence rebound = (PreparedSequence) catalog.find(prepared.id());
        assertSame(replacement, rebound.compile(new Random(0)).playbackSegments().getFirst().animation());
        assertTrue(catalog.isApiRegistrationActive(prepared.id(), registration));
        catalog.replace(List.of());
        assertNull(catalog.find(prepared.id()));
        assertFalse(catalog.isApiRegistrationActive(prepared.id(), registration));
    }

    @Test
    void fileSourcesRetainTheirPathOutsideThePublicDefinition() {
        PreparedAnimation animation = PreparedAnimationFixture.create("test:wave", "Wave");
        EmoteSequence source = sequence(new EmoteSequence.EmoteStep(animation.animation().id(), 1));
        Path path = Path.of("custom", "sequence.json");
        PreparedSequence prepared = PreparedSequence.resolve(new LoadedSequence(path, source), Map.of(animation.id(), animation));
        assertSame(source, prepared.source());
        assertEquals(path, prepared.sourcePath());
        assertEquals(path, prepared.compile(new Random(0)).sourcePath());
    }

    private static EmoteSequence sequence(EmoteSequence.Step... steps) {
        return new EmoteSequence(Identifier.parse("test:sequence"), new EmoteMetadata("Sequence", "test"),
            new EmoteSequence.Settings(0, EmotePlayerBehavior.createDefault()), List.of(steps));
    }
}
