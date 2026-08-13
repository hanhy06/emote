package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.animation.AnimationSkinBinding;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinTaskQueue;
import io.github.hanhy06.emote.skin.mineskin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.config.Config;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSkinManagerTest {
    private static final PlayerSkinRegion HEAD_TEXTURE_KEY = new PlayerSkinRegion(
        PlayerSkinPart.HEAD,
        PlayerSkinSegment.FULL
    );

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
                new AnimationSkinBinding("head", HEAD_TEXTURE_KEY),
                new AnimationSkinBinding("body", bodyTextureKey)
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
            new PlayerSkinBaker(),
            textureStore,
            apiClient,
            bakeExecutor,
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

    private List<AnimationSkinBinding> createSkinParts() {
        return List.of(new AnimationSkinBinding("head", HEAD_TEXTURE_KEY));
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
