package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;

import java.util.Set;
import java.util.UUID;

public interface PlayerSkinProvider extends ConfigListener {
    PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions);

    void setListener(Listener listener);

    void cancelPendingBakes();

    interface Listener {
        default void onReady(UUID playerUuid) {
        }

        default void onFailed(UUID playerUuid) {
        }
    }
}
