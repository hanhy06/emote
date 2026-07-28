package io.github.hanhy06.emote.skin;

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
    private static final PlayerSkinTextureKey HEAD_TEXTURE_KEY = new PlayerSkinTextureKey(
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
                new MineSkinGenerationQueue(),
                new PlayerSkinManager.PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PlayerSkinManager.SkinPreparation result = manager.preparePlayerSkin(null, createSkinParts());

            assertFalse(result.preparing());
            assertEquals(100, result.progressPercent());
            assertEquals(
                "https://textures.example/head",
                result.preparedPlayerSkin().findTextureUrl(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL)
            );
        }
    }

    @Test
    void onlyPreparingStateWaitsForSkinPreparation() {
        assertTrue(new PlayerSkinManager.SkinPreparation(
            null,
            PlayerSkinManager.SkinPreparationState.PREPARING,
            0
        ).preparing());
        assertFalse(new PlayerSkinManager.SkinPreparation(
            null,
            PlayerSkinManager.SkinPreparationState.READY,
            100
        ).preparing());
        assertFalse(new PlayerSkinManager.SkinPreparation(
            null,
            PlayerSkinManager.SkinPreparationState.FAILED,
            0
        ).preparing());
        assertFalse(new PlayerSkinManager.SkinPreparation(
            null,
            PlayerSkinManager.SkinPreparationState.UNAVAILABLE,
            0
        ).preparing());
    }

    @Test
    void preparePlayerSkinSchedulesMissingTextures(@TempDir Path tempDir) {
        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            MineSkinGenerationQueue bakeExecutor = new MineSkinGenerationQueue(() -> executorService);
            PlayerSkinManager manager = createManager(
                new MineSkinCache(tempDir),
                new MineSkinClient(httpClient),
                bakeExecutor,
                new PlayerSkinManager.PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PlayerSkinManager.SkinPreparation result = manager.preparePlayerSkin(null, createSkinParts());

            assertEquals(PlayerSkinManager.SkinPreparationState.PREPARING, result.state());
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
        PlayerSkinTextureKey bodyTextureKey = new PlayerSkinTextureKey(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);

        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            PlayerSkinManager manager = createManager(
                textureStore,
                new MineSkinClient(httpClient),
                new MineSkinGenerationQueue(() -> executorService),
                new PlayerSkinManager.PlayerSkinSource(
                    UUID.randomUUID(),
                    "player",
                    "skin-hash",
                    "https://textures.example/skin",
                    false
                )
            );

            PlayerSkinManager.SkinPreparation result = manager.preparePlayerSkin(null, List.of(
                new EmoteSkinPart("head", PlayerSkinPart.HEAD, PlayerSkinSegment.FULL),
                new EmoteSkinPart("body", bodyTextureKey.skinPart(), bodyTextureKey.skinSegment())
            ));

            assertEquals(PlayerSkinManager.SkinPreparationState.PREPARING, result.state());
            assertEquals(50, result.progressPercent());
            manager.cancelPendingBakes();
        }
    }

    private PlayerSkinManager createManager(
        MineSkinCache textureStore,
        MineSkinClient apiClient,
        MineSkinGenerationQueue bakeExecutor,
        PlayerSkinManager.PlayerSkinSource skinSource
    ) {
        PlayerSkinManager manager = new PlayerSkinManager(
            new PlayerSkinBaker(),
            textureStore,
            apiClient,
            bakeExecutor,
            ignoredPlayer -> skinSource
        );
        manager.onConfigReload(new Config(Config.CURRENT_SCHEMA_VERSION, 6, "api-key", 3));
        return manager;
    }

    private List<EmoteSkinPart> createSkinParts() {
        return List.of(new EmoteSkinPart("head", PlayerSkinPart.HEAD, PlayerSkinSegment.FULL));
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
