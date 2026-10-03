package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;

import java.util.Set;
import java.util.UUID;

public interface PlayerSkinProvider extends ConfigListener {
    PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions);

    void setListener(Listener listener);

    void cancelPendingBakes();

    default void setDefaultRegions(Set<PlayerSkinRegion> regions) {}

    default void setDefaultSource(PlayerSkinSource source) {}

    default PreparedPlayerSkin defaultSkin() { return null; }

    default SkinProcessingStats processingStats() {
        return SkinProcessingStats.unavailable();
    }

    interface Listener {
        default void onDefaultReady() {}
        default void onReady(UUID playerUuid) {
        }

        default void onFailed(UUID playerUuid) {
        }
    }
}
