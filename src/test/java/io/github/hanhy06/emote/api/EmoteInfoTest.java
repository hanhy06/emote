package io.github.hanhy06.emote.api;

import com.google.gson.JsonPrimitive;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteInfoTest {
    @Test
    void exposesCompleteMetadataAndNameConvenienceMethods() {
        EmoteMetadata metadata = new EmoteMetadata(
            "Wave",
            "A wave",
            Map.of("license", new JsonPrimitive("Apache-2.0"))
        );
        EmoteInfo info = new EmoteInfo(
            Identifier.parse("example:wave"),
            metadata,
            EmotePlayerBehavior.createDefault(),
            20,
            EmoteAnimation.LoopMode.ONCE
        );

        assertEquals("Wave", info.name());
        assertEquals("A wave", info.description());
        assertEquals(new JsonPrimitive("Apache-2.0"), info.metadata().additional().get("license"));
    }
}
