package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MineSkinManagerTest {
    @Test
    void completedBakeTaskIsRemoved(@TempDir Path tempDir) {
        PlayerSkinRegion textureKey = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        MineSkinCache cache = new MineSkinCache(tempDir);

        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            MineSkinGenerationQueue queue = new MineSkinGenerationQueue(() -> executorService);
            MineSkinManager manager = new MineSkinManager(
                new PlayerSkinBaker(),
                cache,
                new MineSkinClient(httpClient),
                queue,
                ignored -> {
                },
                ignored -> {
                }
            );
            manager.configure("api-key", 3, 30, 256);
            PlayerSkinManager.PlayerSkinSource source = new PlayerSkinManager.PlayerSkinSource(
                UUID.randomUUID(),
                "player",
                "skin-hash",
                "https://textures.example/skin",
                false
            );

            manager.prepare(source, Set.of(textureKey));
            assertEquals(1, manager.trackedBakeTaskCount());
            assertNotNull(executorService.command);

            cache.save("skin-hash", false, Map.of(textureKey, "https://textures.example/head"));
            executorService.command.run();

            assertEquals(0, manager.trackedBakeTaskCount());
            manager.cancelPendingBakes();
        }
    }

    @Test
    void cancelledBakeTaskDoesNotNotifyCompletion(@TempDir Path tempDir) {
        PlayerSkinRegion textureKey = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        MineSkinCache cache = new MineSkinCache(tempDir);
        AtomicInteger completionNotifications = new AtomicInteger();

        try (CapturingExecutorService executorService = new CapturingExecutorService();
             HttpClient httpClient = MineSkinClient.createHttpClient()) {
            MineSkinGenerationQueue queue = new MineSkinGenerationQueue(() -> executorService);
            MineSkinManager manager = new MineSkinManager(
                new PlayerSkinBaker(),
                cache,
                new MineSkinClient(httpClient),
                queue,
                ignored -> completionNotifications.incrementAndGet(),
                ignored -> {
                }
            );
            manager.configure("api-key", 3, 30, 256);
            PlayerSkinManager.PlayerSkinSource source = new PlayerSkinManager.PlayerSkinSource(
                UUID.randomUUID(),
                "player",
                "skin-hash",
                "https://textures.example/skin",
                false
            );

            manager.prepare(source, Set.of(textureKey));
            assertNotNull(executorService.command);
            cache.save("skin-hash", false, Map.of(textureKey, "https://textures.example/head"));

            manager.cancelPendingBakes();
            executorService.command.run();

            assertEquals(0, completionNotifications.get());
            assertEquals(0, manager.trackedBakeTaskCount());
        }
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
