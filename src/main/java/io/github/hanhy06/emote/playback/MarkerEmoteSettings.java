package io.github.hanhy06.emote.playback;

import net.minecraft.world.item.component.CustomData;

public record MarkerEmoteSettings(String emoteId, String skinName) {
    public static final MarkerEmoteSettings EMPTY = new MarkerEmoteSettings("", "");

    public MarkerEmoteSettings {
        emoteId = emoteId.strip();
        skinName = skinName.strip();
    }

    public static MarkerEmoteSettings read(CustomData data) {
        var tag = data.copyTag();
        return new MarkerEmoteSettings(tag.getStringOr("emote", ""), tag.getStringOr("emote_skin", ""));
    }
}
