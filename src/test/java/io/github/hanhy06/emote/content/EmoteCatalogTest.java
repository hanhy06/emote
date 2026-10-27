package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteLoadException;
import io.github.hanhy06.emote.api.sequence.EmoteSequence;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmoteCatalogTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
    }

    @Test
    void reusesSequencePreparationUntilAnimationDefinitionsChange() throws Exception {
        var catalog = new EmoteCatalog();
        var notifications = new ArrayList<List<PreparedEmote>>();
        catalog.addListener(notifications::add);
        var original = animation(2);
        catalog.replace(List.of(original));
        var first = sequence("test:first", original);
        catalog.register(first);
        assertSame(first, catalog.find(first.id()));
        assertSame(first, notifications.getLast().stream().filter(emote -> emote.id().equals(first.id())).findFirst().orElseThrow());

        var second = sequence("test:second", original);
        var registration = catalog.register(second);
        assertSame(first, catalog.find(first.id()));
        assertSame(second, catalog.find(second.id()));
        assertThrows(IllegalArgumentException.class, () -> catalog.register(second));
        assertTrue(catalog.unregister(second.id(), registration));
        assertSame(first, catalog.find(first.id()));

        var replacement = animation(4);
        catalog.replace(List.of(replacement));
        var resolved = (PreparedSequence) catalog.find(first.id());
        assertNotSame(first, resolved);
        assertEquals(4, resolved.durationTicks());
        assertSame(replacement, resolved.animations().getFirst());
        assertTrue(notifications.getLast().contains(resolved));
    }

    @Test
    void removesSequencesAfterTheirRegisteredAnimationDisappears() throws Exception {
        var catalog = new EmoteCatalog();
        var notifications = new ArrayList<List<PreparedEmote>>();
        catalog.addListener(notifications::add);
        var animation = animation(2);
        var animationRegistration = catalog.register(animation);
        var sequence = sequence("test:dependent", animation);
        var sequenceRegistration = catalog.register(sequence);
        assertTrue(catalog.unregister(animation.id(), animationRegistration));
        assertNull(catalog.find(sequence.id()));
        assertFalse(catalog.isApiRegistrationActive(sequence.id(), sequenceRegistration));
        assertTrue(notifications.getLast().isEmpty());
        assertThrows(EmoteLoadException.class, () -> PreparedSequence.prepare(sequence.model(), Map.of()));
        assertEquals(0, catalog.size());
    }

    private static PreparedAnimation animation(int duration) throws Exception {
        String json = """
            {"type":"animation","schema_version":5,"id":"test:base","metadata":{"name":"Base","description":""},
             "nodes":{"n":{"transform":[],"attachments":{}}},"animation":{"duration":"%dt","tracks":[]}}
            """.formatted(duration);
        return PreparedAnimation.prepare(new AnimationJsonParser().parse(Path.of("base.json"), json.getBytes(StandardCharsets.UTF_8)), registries);
    }

    private static PreparedSequence sequence(String id, PreparedAnimation animation) throws Exception {
        return PreparedSequence.prepare(new EmoteSequence(Identifier.parse(id), animation.metadata(),
            new EmoteSequence.Settings(0, animation.playerBehavior()),
            List.of(new EmoteSequence.AnimationStep(animation.model().id(), 1))), Map.of(animation.id(), animation));
    }
}
