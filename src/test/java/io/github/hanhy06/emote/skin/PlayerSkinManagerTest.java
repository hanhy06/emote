package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.data.Config;
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
                new MineSkinManager.GenerationQueue(),
                new PlayerSkinManager.PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PreparedPlayerSkin result = manager.preparePlayerSkin(null, createSkinParts());

            assertNotNull(result);
            assertEquals(
                "https://textures.example/head",
                result.findTextureUrl(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL)
            );
        }
    }

    @Test
    void preparePlayerSkinSchedulesMissingTextures(@TempDir Path tempDir) {
        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            MineSkinManager.GenerationQueue bakeExecutor = new MineSkinManager.GenerationQueue(() -> executorService);
            PlayerSkinManager manager = createManager(
                new MineSkinCache(tempDir),
                new MineSkinClient(httpClient),
                bakeExecutor,
                new PlayerSkinManager.PlayerSkinSource(UUID.randomUUID(), "player", "skin-hash", "https://textures.example/skin", false)
            );

            PreparedPlayerSkin result = manager.preparePlayerSkin(null, createSkinParts());

            assertNull(result);
            assertNotNull(executorService.command);

            manager.cancelPendingBakes();
            assertTrue(executorService.isShutdown());
        }
    }

    private PlayerSkinManager createManager(
        MineSkinCache textureStore,
        MineSkinClient apiClient,
        MineSkinManager.GenerationQueue bakeExecutor,
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
