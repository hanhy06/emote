package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSkinManagerTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);

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
            new SkinBinding("normal_head", ParticipantRole.INITIATOR, HEAD),
            new SkinBinding("normal_upper", ParticipantRole.INITIATOR, upper),
            new SkinBinding("normal_lower", ParticipantRole.INITIATOR, lower),
            new SkinBinding("jointed_upper", ParticipantRole.PARTNER, upper),
            new SkinBinding("jointed_joint", ParticipantRole.PARTNER, joint)
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
        manager.setModelBindings(List.of(new SkinBinding("head", ParticipantRole.INITIATOR, HEAD)));

        manager.checkPlayerSkin(null);
        assertTrue(provider.requests.isEmpty());
        source.set(new PlayerSkinSource(playerId, "player", "first", "https://textures.example/first", false));
        manager.checkPlayerSkin(null);
        assertTrue(refreshedPlayers.isEmpty());
        source.set(new PlayerSkinSource(playerId, "player", "second", "https://textures.example/second", false));
        manager.checkPlayerSkin(null);
        assertEquals(List.of(playerId), refreshedPlayers);

        PlayerSkinRegion body = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);
        manager.preparePlayerSkin(null, List.of(new SkinBinding("body", ParticipantRole.INITIATOR, body)));
        assertEquals(Set.of(HEAD, body), provider.requests.getLast());
    }

    @Test
    void onlyPreparingStateWaitsForSkinPreparation() {
        assertTrue(new PlayerSkinPreparation(null, PlayerSkinPreparation.State.PREPARING, 0).preparing());
        assertFalse(new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100).preparing());
        assertFalse(new PlayerSkinPreparation(null, PlayerSkinPreparation.State.FAILED, 0).preparing());
        assertFalse(new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0).preparing());
    }

    private static final class RecordingProvider implements PlayerSkinProvider {
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
