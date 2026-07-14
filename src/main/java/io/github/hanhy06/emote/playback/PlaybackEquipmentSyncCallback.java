package io.github.hanhy06.emote.playback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public interface PlaybackEquipmentSyncCallback {
    Event<PlaybackEquipmentSyncCallback> EVENT = EventFactory.createArrayBacked(
        PlaybackEquipmentSyncCallback.class,
        callbacks -> (player, changedItems) -> {
            for (PlaybackEquipmentSyncCallback callback : callbacks) {
                callback.afterEquipmentSync(player, changedItems);
            }
        }
    );

    void afterEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems);
}
