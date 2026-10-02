package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSkinManagerTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);

    @Test
    void defaultSkinFillsMissingRegionsAfterPersonalCacheAndPreservesPreparationState() {
        PlayerSkinRegion body = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);
        RecordingProvider provider = new RecordingProvider();
        provider.fallback = new PreparedPlayerSkin(java.util.Map.of(HEAD, "default-head", body, "default-body"));
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> null);
        PlayerSkinPreparation failed = new PlayerSkinPreparation(new PreparedPlayerSkin(java.util.Map.of(HEAD, "personal-head")),
            PlayerSkinPreparation.State.FAILED, 50);
        PlayerSkinPreparation combined = manager.withDefaultSkin(failed, Set.of(HEAD, body));
        assertEquals("personal-head", combined.preparedPlayerSkin().findTextureUrl(HEAD));
        assertEquals("default-body", combined.preparedPlayerSkin().findTextureUrl(body));
        assertEquals(PlayerSkinPreparation.State.FAILED, combined.state());
        PlayerSkinPreparation preparing = new PlayerSkinPreparation(null, PlayerSkinPreparation.State.PREPARING, 0);
        assertEquals(preparing, manager.withDefaultSkin(preparing, Set.of(HEAD, body)));
        PlayerSkinPreparation missing = manager.preparePlayerSkin(null, List.of(new SkinBinding("head", HEAD)));
        assertEquals("default-head", missing.preparedPlayerSkin().findTextureUrl(HEAD));
        assertEquals(PlayerSkinPreparation.State.UNAVAILABLE, missing.state());
        PlayerSkinManager failedLookup = new PlayerSkinManager(provider, ignored -> { throw new IllegalStateException("API failed"); });
        assertEquals("default-head", failedLookup.preparePlayerSkin(null,
            List.of(new SkinBinding("head", HEAD))).preparedPlayerSkin().findTextureUrl(HEAD));
    }

    @Test
    void preparesSharedModelRegionsOnJoinAndSkinChangeOnly() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<PlayerSkinSource> source = new AtomicReference<>(new PlayerSkinSource(
            playerId, "player", "first", "https://textures.example/first", false
        ));
        RecordingProvider provider = new RecordingProvider();
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> source.get());
        PlayerSkinRegion upper = new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(0, 4));
        PlayerSkinRegion lower = new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(4, 12));
        PlayerSkinRegion joint = new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(4, 6));
        manager.setModelBindings(List.of(
            new SkinBinding("normal_head", HEAD),
            new SkinBinding("normal_upper", upper),
            new SkinBinding("normal_lower", lower),
            new SkinBinding("jointed_upper", upper),
            new SkinBinding("jointed_joint", joint)
        ));

        manager.checkPlayerSkin(null);
        manager.checkPlayerSkin(null);
        assertEquals(1, provider.requests.size());
        assertEquals(Set.of(HEAD, upper, lower, joint), provider.requests.getFirst());

        source.set(new PlayerSkinSource(playerId, "player", "second", "https://textures.example/second", false));
        manager.checkPlayerSkin(null);
        manager.checkPlayerSkin(null);
        assertEquals(2, provider.requests.size());

        source.set(new PlayerSkinSource(playerId, "player", "second", "https://textures.example/second", true));
        manager.checkPlayerSkin(null);
        assertEquals(3, provider.requests.size());

        manager.removePlayer(playerId);
        manager.checkPlayerSkin(null);
        manager.checkPlayerSkin(null);
        assertEquals(4, provider.requests.size());
    }

    @Test
    void cachedSkinChangeRefreshesActivePlaybackAndPlaybackUsesAllModelRegions() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<PlayerSkinSource> source = new AtomicReference<>();
        RecordingProvider provider = new RecordingProvider();
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> source.get());
        List<UUID> refreshedPlayers = new ArrayList<>();
        manager.addReadyListener(refreshedPlayers::add);
        manager.setModelBindings(List.of(new SkinBinding("head", HEAD)));

        manager.checkPlayerSkin(null);
        assertTrue(provider.requests.isEmpty());
        source.set(new PlayerSkinSource(playerId, "player", "first", "https://textures.example/first", false));
        manager.checkPlayerSkin(null);
        assertTrue(refreshedPlayers.isEmpty());
        source.set(new PlayerSkinSource(playerId, "player", "second", "https://textures.example/second", false));
        manager.checkPlayerSkin(null);
        assertEquals(List.of(playerId), refreshedPlayers);

        PlayerSkinRegion body = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);
        manager.preparePlayerSkin(null, List.of(new SkinBinding("body", body)));
        assertEquals(Set.of(HEAD, body), provider.requests.getLast());
    }

    private static final class RecordingProvider implements PlayerSkinProvider {
        private PreparedPlayerSkin fallback;
        @Override public PreparedPlayerSkin defaultSkin() { return this.fallback; }
        private final List<Set<PlayerSkinRegion>> requests = new ArrayList<>();

        @Override
        public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
            this.requests.add(Set.copyOf(requiredRegions));
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }

        @Override public void setListener(Listener listener) {}
        @Override public void cancelPendingBakes() {}
        @Override public void onConfigReload(Config config) {}
    }
}
