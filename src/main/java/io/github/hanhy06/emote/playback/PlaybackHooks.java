package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.PlaybackStopReason;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public final class PlaybackHooks {
    public static final Event<Interruption> INTERRUPTION = EventFactory.createArrayBacked(
        Interruption.class,
        callbacks -> (player, reason) -> {
            for (Interruption callback : callbacks) {
                callback.interruptPlayback(player, reason);
            }
        }
    );
    public static final Event<EquipmentSync> EQUIPMENT_SYNC = EventFactory.createArrayBacked(
        EquipmentSync.class,
        callbacks -> (player, changedItems) -> {
            for (EquipmentSync callback : callbacks) {
                callback.afterEquipmentSync(player, changedItems);
            }
        }
    );

    private PlaybackHooks() {
    }

    @FunctionalInterface
    public interface Interruption {
        void interruptPlayback(ServerPlayer player, PlaybackStopReason reason);
    }

    @FunctionalInterface
    public interface EquipmentSync {
        void afterEquipmentSync(ServerPlayer player, Map<EquipmentSlot, ItemStack> changedItems);
    }
}
