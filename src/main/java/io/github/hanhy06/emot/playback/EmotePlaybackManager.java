package io.github.hanhy06.emot.playback;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EmotePlaybackManager {
	private final Map<UUID, ActiveEmote> activeEmoteMap = new ConcurrentHashMap<>();

	public void startEmote(ServerPlayer player, String namespace, String animationName) {
		this.activeEmoteMap.put(player.getUUID(), new ActiveEmote(player.getUUID(), namespace, animationName));
	}

	public Optional<ActiveEmote> stopEmote(UUID playerUuid) {
		return Optional.ofNullable(this.activeEmoteMap.remove(playerUuid));
	}

	public Optional<ActiveEmote> findActiveEmote(UUID playerUuid) {
		return Optional.ofNullable(this.activeEmoteMap.get(playerUuid));
	}

	public void clear() {
		this.activeEmoteMap.clear();
	}
}
