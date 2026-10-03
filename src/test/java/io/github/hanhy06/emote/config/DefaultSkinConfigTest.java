package io.github.hanhy06.emote.config;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultSkinConfigTest {
    @Test
    void readsAndWritesOptionalPlayerName() {
        ConfigJsonCodec codec = new ConfigJsonCodec();
        JsonObject json = new JsonObject();
        assertEquals("", codec.readConfig(json).defaultSkin());
        json.add("default_skin", JsonNull.INSTANCE);
        assertEquals("", codec.readConfig(json).defaultSkin());
        json.addProperty("default_skin", " PlayerName ");
        Config config = codec.readConfig(json);
        assertEquals("PlayerName", config.defaultSkin());
        assertEquals("PlayerName", codec.writeConfig(config).get("default_skin").getAsString());
        json.addProperty("default_skin", "bad name");
        assertThrows(IllegalArgumentException.class, () -> codec.readConfig(json));
    }
}
