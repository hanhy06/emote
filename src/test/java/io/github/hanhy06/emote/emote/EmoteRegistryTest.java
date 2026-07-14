package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static io.github.hanhy06.emote.test.EmoteDefinitionFixture.create;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmoteRegistryTest {
    @Test
    void findDefinitionByCommandNameIgnoresTurkishLocaleCasing() {
        Locale previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        try {
            EmoteRegistry registry = new EmoteRegistry();
            registry.replaceDefinitions(List.of(create("idle", "idle", "Idle")));

            assertNotNull(registry.findDefinitionByCommandName("IDLE"));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
