package io.github.hanhy06.emote.playback;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public record MarkerEmoteSettings(String emoteId, String skinName) {
    public static final MarkerEmoteSettings EMPTY = new MarkerEmoteSettings("", "");

    public MarkerEmoteSettings {
        emoteId = emoteId.strip();
        skinName = skinName.strip();
    }

    public static MarkerEmoteSettings read(ValueInput input) {
        return new MarkerEmoteSettings(input.getStringOr("emote", ""), input.getStringOr("emote_skin", ""));
    }

    public void write(ValueOutput output) {
        if (!emoteId.isEmpty()) output.putString("emote", emoteId);
        if (!skinName.isEmpty()) output.putString("emote_skin", skinName);
    }
}
