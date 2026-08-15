package io.github.hanhy06.emote.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class EmoteCooldowns {
    private final Map<UUID, Map<String, Long>> readyTicks = new HashMap<>();

    long remainingTicks(UUID playerId, String emoteId, long currentTick) {
        Map<String, Long> playerCooldowns = this.readyTicks.get(playerId);
        if (playerCooldowns == null) {
            return 0L;
        }
        long remaining = playerCooldowns.getOrDefault(emoteId, currentTick) - currentTick;
        if (remaining <= 0L) {
            playerCooldowns.remove(emoteId);
            if (playerCooldowns.isEmpty()) {
                this.readyTicks.remove(playerId);
            }
            return 0L;
        }
        return remaining;
    }

    void start(UUID playerId, String emoteId, long currentTick, int cooldownTicks) {
        if (cooldownTicks > 0) {
            this.readyTicks.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(emoteId, currentTick + cooldownTicks);
        }
    }

    void clear() {
        this.readyTicks.clear();
    }
}
