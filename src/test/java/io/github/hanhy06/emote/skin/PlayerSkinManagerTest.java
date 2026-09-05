package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.api.ParticipantRole;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.mineskin.MineSkinTaskQueue;
import io.github.hanhy06.emote.skin.model.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSkinManagerTest {
    private static final PlayerSkinRegion HEAD_TEXTURE_KEY = new PlayerSkinRegion(
        PlayerSkinPart.HEAD,
        PlayerSkinSegment.FULL
    );

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
            new SkinBinding("normal_head", ParticipantRole.INITIATOR, HEAD_TEXTURE_KEY),
            new SkinBinding("normal_upper", ParticipantRole.INITIATOR, upper),
            new SkinBinding("normal_lower", ParticipantRole.INITIATOR, lower),
            new SkinBinding("jointed_upper", ParticipantRole.PARTNER, upper),
            new SkinBinding("jointed_joint", ParticipantRole.PARTNER, joint)
        ));

        manager.checkPlayerSkin(null);
        manager.checkPlayerSkin(null);
        assertEquals(1, provider.requests.size());
        assertEquals(Set.of(HEAD_TEXTURE_KEY, upper, lower, joint), provider.requests.getFirst());

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
        AtomicReference<PlayerSkinSource> source = new AtomicReference<>(null);
        RecordingProvider provider = new RecordingProvider();
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> source.get());
        List<UUID> refreshedPlayers = new ArrayList<>();
        manager.addReadyListener(refreshedPlayers::add);
        manager.setModelBindings(createSkinParts());
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
        assertEquals(Set.of(HEAD_TEXTURE_KEY, body), provider.requests.getLast());
    }

    private static final class RecordingProvider implements PlayerSkinProvider {
        private final List<Set<PlayerSkinRegion>> requests = new ArrayList<>();

        @Override public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> requiredRegions) {
            this.requests.add(Set.copyOf(requiredRegions));
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }

        @Override public void setListener(Listener listener) {}
        @Override public void cancelPendingBakes() {}
        @Override public void onConfigReload(Config config) {}
    }

    @Test
    void preparePlayerSkinReturnsStoredTextures(@TempDir Path tempDir) {
        MineSkinCache textureStore = new MineSkinCache(tempDir);
        textureStore.save("skin-hash", false, Map.of(HEAD_TEXTURE_KEY, "https://textures.example/head"));

        try (HttpClient httpClient = MineSkinClient.createHttpClient()) {
            PlayerSkinManager manager = createManager(
                textureStore,
                new MineSkinClient(httpClient),
                new MineSkinTaskQueue(),
                new PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PlayerSkinPreparation result = manager.preparePlayerSkin(null, createSkinParts());

            assertFalse(result.preparing());
            assertEquals(100, result.progressPercent());
            assertEquals(
                "https://textures.example/head",
                result.preparedPlayerSkin().findTextureUrl(HEAD_TEXTURE_KEY)
            );
        }
    }

    @Test
    void onlyPreparingStateWaitsForSkinPreparation() {
        assertTrue(new PlayerSkinPreparation(
            null,
            PlayerSkinPreparation.State.PREPARING,
            0
        ).preparing());
        assertFalse(new PlayerSkinPreparation(
            null,
            PlayerSkinPreparation.State.READY,
            100
        ).preparing());
        assertFalse(new PlayerSkinPreparation(
            null,
            PlayerSkinPreparation.State.FAILED,
            0
        ).preparing());
        assertFalse(new PlayerSkinPreparation(
            null,
            PlayerSkinPreparation.State.UNAVAILABLE,
            0
        ).preparing());
    }

    @Test
    void preparePlayerSkinSchedulesMissingTextures(@TempDir Path tempDir) {
        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            MineSkinTaskQueue bakeExecutor = new MineSkinTaskQueue(() -> executorService);
            PlayerSkinManager manager = createManager(
                new MineSkinCache(tempDir),
                new MineSkinClient(httpClient),
                bakeExecutor,
                new PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PlayerSkinPreparation result = manager.preparePlayerSkin(null, createSkinParts());

            assertEquals(PlayerSkinPreparation.State.PREPARING, result.state());
            assertEquals(0, result.progressPercent());
            assertNull(result.preparedPlayerSkin());
            assertNotNull(executorService.command);

            manager.cancelPendingBakes();
            assertTrue(executorService.isShutdown());
        }
    }

    @Test
    void preparePlayerSkinReportsProgressForRequestedParts(@TempDir Path tempDir) {
        MineSkinCache textureStore = new MineSkinCache(tempDir);
        textureStore.save("skin-hash", false, Map.of(HEAD_TEXTURE_KEY, "https://textures.example/head"));
        PlayerSkinRegion bodyTextureKey = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);

        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            PlayerSkinManager manager = createManager(
                textureStore,
                new MineSkinClient(httpClient),
                new MineSkinTaskQueue(() -> executorService),
                new PlayerSkinSource(
                    UUID.randomUUID(),
                    "player",
                    "skin-hash",
                    "https://textures.example/skin",
                    false
                )
            );

            PlayerSkinPreparation result = manager.preparePlayerSkin(null, List.of(
                new SkinBinding("head", ParticipantRole.INITIATOR, HEAD_TEXTURE_KEY),
                new SkinBinding("body", ParticipantRole.INITIATOR, bodyTextureKey)
            ));

            assertEquals(PlayerSkinPreparation.State.PREPARING, result.state());
            assertEquals(50, result.progressPercent());
            manager.cancelPendingBakes();
        }
    }

    private PlayerSkinManager createManager(
        MineSkinCache textureStore,
        MineSkinClient apiClient,
        MineSkinTaskQueue bakeExecutor,
        PlayerSkinSource skinSource
    ) {
        PlayerSkinManager manager = new PlayerSkinManager(
            new MineSkinProvider(new PlayerSkinBaker(), textureStore, apiClient, bakeExecutor),
            ignoredPlayer -> skinSource
        );
        manager.onConfigReload(new Config(
            Config.CURRENT_SCHEMA_VERSION,
            6,
            "api-key",
            3,
            Config.DEFAULT_MINESKIN_CACHE_RETENTION_DAYS,
            Config.DEFAULT_MINESKIN_CACHE_MAX_MIB,
            Config.DEFAULT_MAX_ACTIVE_DISPLAY_ENTITIES
        ));
        return manager;
    }

    private List<SkinBinding> createSkinParts() {
        return List.of(new SkinBinding("head", ParticipantRole.INITIATOR, HEAD_TEXTURE_KEY));
    }

    private static final class CapturingExecutorService extends AbstractExecutorService {
        private boolean shutdown;
        private Runnable command;

        @Override
        public void shutdown() {
            this.shutdown = true;
        }

        @Override
        public @NonNull List<Runnable> shutdownNow() {
            this.shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
            return this.shutdown;
        }

        @Override
        public void execute(@NonNull Runnable command) {
            this.command = command;
        }
    }
}
