package io.github.hanhy06.emote.emote;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmoteRegistryTest {
    @Test
    void findDefinitionByCommandNameIgnoresTurkishLocaleCasing() {
        Locale previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        try {
            EmoteRegistry registry = new EmoteRegistry();
            registry.replaceDefinitions(List.of(new EmoteDefinition(
                "idle",
                "Idle",
                "Idle animation",
                "idle",
                "a/default/play_anim_loop",
                true,
                Path.of("test-pack"),
                1,
                List.of()
            )));

            assertNotNull(registry.findDefinitionByCommandName("IDLE"));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }
}
