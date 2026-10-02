package io.github.hanhy06.emote.playback;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerEmoteSettingsTest {
    @Test
    void readsEmoteAndSkinFromCustomData() {
        var tag = new CompoundTag();
        tag.putString("emote", " wave ");
        tag.putString("emote_skin", " Steve ");

        assertEquals(new MarkerEmoteSettings("wave", "Steve"), MarkerEmoteSettings.read(CustomData.of(tag)));
    }

    @Test
    void defaultsMissingAndNonStringSettingsToEmpty() {
        assertEquals(MarkerEmoteSettings.EMPTY, MarkerEmoteSettings.read(CustomData.EMPTY));
        var tag = new CompoundTag();
        tag.putInt("emote", 1);
        tag.putBoolean("emote_skin", true);

        assertEquals(MarkerEmoteSettings.EMPTY, MarkerEmoteSettings.read(CustomData.of(tag)));
    }

    @Test
    void readsUpdatedAndRemovedSettings() {
        var tag = new CompoundTag();
        tag.putString("emote", "wave");
        var data = CustomData.of(tag);
        var updated = data.update(value -> value.putString("emote", "dance"));

        assertEquals("wave", MarkerEmoteSettings.read(data).emoteId());
        assertEquals("dance", MarkerEmoteSettings.read(updated).emoteId());
        assertEquals(MarkerEmoteSettings.EMPTY, MarkerEmoteSettings.read(updated.update(value -> value.remove("emote"))));
    }
}
