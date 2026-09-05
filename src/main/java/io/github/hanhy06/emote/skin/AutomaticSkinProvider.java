package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.model.*;

import java.util.*;
import java.util.function.BooleanSupplier;

public final class AutomaticSkinProvider implements PlayerSkinProvider {
    private final BooleanSupplier hasAccounts;
    private final PlayerSkinProvider accountProvider;
    private final PlayerSkinProvider mineSkinProvider;

    public AutomaticSkinProvider(BooleanSupplier hasAccounts, PlayerSkinProvider accountProvider, PlayerSkinProvider mineSkinProvider) {
        this.hasAccounts = hasAccounts;
        this.accountProvider = accountProvider;
        this.mineSkinProvider = mineSkinProvider;
    }

    @Override public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> regions) {
        PlayerSkinProvider selected = this.hasAccounts.getAsBoolean() ? this.accountProvider : this.mineSkinProvider;
        return selected.prepare(source, regions);
    }

    @Override public void onConfigReload(Config config) {
        this.accountProvider.onConfigReload(config);
        this.mineSkinProvider.onConfigReload(config);
        if (!this.hasAccounts.getAsBoolean() && config.mineSkinApiKey().isBlank()) {
            EmoteMod.LOGGER.warn("No bake accounts or MineSkin API key configured; only cached skin textures are available");
        }
    }

    @Override public void setListener(Listener listener) {
        this.accountProvider.setListener(listener);
        this.mineSkinProvider.setListener(listener);
    }

    @Override public void cancelPendingBakes() {
        this.accountProvider.cancelPendingBakes();
        this.mineSkinProvider.cancelPendingBakes();
    }
}
