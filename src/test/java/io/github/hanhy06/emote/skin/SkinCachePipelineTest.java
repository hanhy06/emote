package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.account.*;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SkinCachePipelineTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, 0, 1);
    private static final PlayerSkinRegion BODY = new PlayerSkinRegion(PlayerSkinPart.BODY, 0, 1);
    private static final String HASH = "a".repeat(64);
    @TempDir Path directory;

    @Test
    void loadsColdCacheWithoutProviderAndCoalescesSubscribersAndRegions() throws Exception {
        var cache = new SkinCache(this.directory);
        var textures = Map.of(HEAD, "head", BODY, "body");
        assertTrue(cache.save(HASH, false, textures, cache.epoch()));
        cache.clearMemory();
        var coordinator = coordinator(cache);
        var ready = new LinkedBlockingQueue<UUID>();
        var unavailable = new LinkedBlockingQueue<UUID>();
        var failed = new LinkedBlockingQueue<UUID>();
        coordinator.setListener(new SkinBakeCoordinator.Listener() {
            public void onReady(UUID player, long generation) { ready.add(player); }
            public void onUnavailable(UUID player, long generation) { unavailable.add(player); }
            public void onFailed(UUID player, long generation) { failed.add(player); }
        });
        var first = source(UUID.randomUUID(), HASH);
        var second = source(UUID.randomUUID(), HASH);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var gate = new IoGate(cache)) {
                assertEquals(PlayerSkinPreparation.State.PREPARING,
                    tasks.submit(() -> coordinator.prepare(first, Set.of(HEAD))).get(2, TimeUnit.SECONDS).state());
                assertEquals(PlayerSkinPreparation.State.PREPARING,
                    tasks.submit(() -> coordinator.prepare(second, Set.of(BODY))).get(2, TimeUnit.SECONDS).state());
                var stats = coordinator.processingStats();
                assertEquals(1, stats.activeJobs() + stats.queuedJobs());
                assertTrue(ready.isEmpty());
            }
            assertEquals(Set.of(first.playerUuid(), second.playerUuid()), Set.of(ready.poll(5, TimeUnit.SECONDS), ready.poll(5, TimeUnit.SECONDS)));
            assertEquals(textures, coordinator.prepare(first, Set.of(HEAD, BODY)).textures());
            var missing = source(UUID.randomUUID(), "b".repeat(64));
            assertEquals(PlayerSkinPreparation.State.PREPARING, coordinator.prepare(missing, Set.of(HEAD)).state());
            assertEquals(missing.playerUuid(), unavailable.poll(5, TimeUnit.SECONDS));
            assertEquals(PlayerSkinPreparation.State.UNAVAILABLE, coordinator.prepare(missing, Set.of(HEAD)).state());
            assertTrue(failed.isEmpty());
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void keepsMemoryReadsAndCancellationResponsiveWhileIoIsBlocked() throws Exception {
        var cache = new SkinCache(this.directory);
        assertTrue(cache.save(HASH, false, Map.of(HEAD, "head"), cache.epoch()));
        long oldEpoch = cache.epoch();
        var coordinator = coordinator(cache);
        var callbacks = new LinkedBlockingQueue<UUID>();
        coordinator.setListener(new SkinBakeCoordinator.Listener() {
            public void onReady(UUID player, long generation) { callbacks.add(player); }
            public void onUnavailable(UUID player, long generation) { callbacks.add(player); }
        });
        ExecutorService pendingWorker;
        Future<Boolean> staleSave;
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var gate = new IoGate(cache)) {
                var warm = source(UUID.randomUUID(), HASH);
                assertEquals(PlayerSkinPreparation.State.READY,
                    tasks.submit(() -> coordinator.prepare(warm, Set.of(HEAD))).get(2, TimeUnit.SECONDS).state());
                staleSave = tasks.submit(() -> cache.save(HASH, false, Map.of(BODY, "stale"), oldEpoch));
                assertEquals(PlayerSkinPreparation.State.PREPARING,
                    tasks.submit(() -> coordinator.prepare(source(UUID.randomUUID(), "c".repeat(64)), Set.of(HEAD))).get(2, TimeUnit.SECONDS).state());
                tasks.submit(() -> coordinator.onConfigReload(defaultConfig("Player"))).get(2, TimeUnit.SECONDS);
                pendingWorker = (ExecutorService) field(coordinator, "executor");
                tasks.submit(coordinator::cancelPendingBakes).get(2, TimeUnit.SECONDS);
                assertNull(cache.peek(HASH, false));
            }
            assertFalse(staleSave.get(2, TimeUnit.SECONDS));
            assertTrue(pendingWorker.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(cache.peek(HASH, false));
            assertTrue(callbacks.isEmpty());
            assertEquals(Map.of(HEAD, "head"), cache.load(HASH, false, cache.epoch()));
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void mergesConcurrentRegionSavesAndDoesNotPublishFailedWrites() throws Exception {
        var cache = new SkinCache(this.directory.resolve("cache"));
        long epoch = cache.epoch();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = tasks.submit(() -> { start.await(); return cache.save(HASH, false, Map.of(HEAD, "head"), epoch); });
            var second = tasks.submit(() -> { start.await(); return cache.save(HASH, false, Map.of(BODY, "body"), epoch); });
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        }
        var reopened = new SkinCache(this.directory.resolve("cache"));
        assertEquals(Map.of(HEAD, "head", BODY, "body"), reopened.load(HASH, false, reopened.epoch()));
        Path blocked = this.directory.resolve("not-a-directory");
        Files.writeString(blocked, "file");
        var failed = new SkinCache(blocked);
        assertFalse(failed.save(HASH, false, Map.of(HEAD, "head"), failed.epoch()));
        assertFalse(failed.saveContent("b".repeat(64), "texture", failed.epoch()));
        assertEquals(Map.of(), failed.peek(HASH, false));
        assertNull(failed.loadContent("b".repeat(64), failed.epoch()));
    }

    @Test
    void cleanupKeepsRecentlyUsedMemoryEntriesBeforeDeferredTimestampWrite() throws Exception {
        var cache = new SkinCache(this.directory);
        assertTrue(cache.save(HASH, false, Map.of(HEAD, "head"), cache.epoch()));
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(this.directory.resolve(HASH + "-classic.json"), FileTime.fromMillis(now - TimeUnit.DAYS.toMillis(40)));
        var kept = cache.cleanup(TimeUnit.DAYS.toMillis(30), Long.MAX_VALUE, now, cache.epoch());
        assertEquals(0, kept.expiredFilesDeleted());
        assertEquals(Map.of(HEAD, "head"), cache.peek(HASH, false));
        cache.clearMemory();
        var expired = cache.cleanup(TimeUnit.DAYS.toMillis(30), Long.MAX_VALUE, now, cache.epoch());
        assertEquals(1, expired.expiredFilesDeleted());
    }

    @Test
    void joinsContentLookupWithoutHoldingCoordinatorDuringDiskWork() throws Exception {
        var cache = new SkinCache(this.directory);
        byte[] png = {1, 2, 3};
        String key = SkinCache.createContentKey(png, false);
        assertTrue(cache.saveContent(key, "cached-texture", cache.epoch()));
        var coordinator = coordinator(cache);
        var texture = SkinBakeCoordinator.class.getDeclaredMethod("texture", byte[].class, boolean.class, long.class, long.class);
        texture.setAccessible(true);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?> first;
            try (var gate = new IoGate(cache)) {
                first = (CompletableFuture<?>) tasks.submit(() -> texture.invoke(coordinator, png, false, 0L, cache.epoch())).get(2, TimeUnit.SECONDS);
                var second = tasks.submit(() -> texture.invoke(coordinator, png, false, 0L, cache.epoch())).get(2, TimeUnit.SECONDS);
                assertSame(first, second);
                assertFalse(first.isDone());
                tasks.submit(coordinator::processingStats).get(2, TimeUnit.SECONDS);
            }
            assertEquals("cached-texture", first.get(5, TimeUnit.SECONDS));
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void ignoresOldDefaultRevisionAndRetainsCompleteFallbackUntilReplacementIsReady() throws Exception {
        var cache = new SkinCache(this.directory);
        var original = Map.of(HEAD, "old-head", BODY, "old-body");
        assertTrue(cache.saveDefault(new SkinCache.DefaultSkin("Player", HASH, "original", false, original), cache.epoch()));
        var partialHash = "b".repeat(64);
        var completeHash = "c".repeat(64);
        var replacement = Map.of(HEAD, "new-head", BODY, "new-body");
        assertTrue(cache.save(partialHash, false, Map.of(HEAD, "partial-head"), cache.epoch()));
        assertTrue(cache.save(completeHash, false, replacement, cache.epoch()));
        var coordinator = coordinator(cache);
        var ready = new LinkedBlockingQueue<Map<PlayerSkinRegion, String>>();
        coordinator.setListener(new SkinBakeCoordinator.Listener() {
            public void onDefaultReady(long generation, long revision) {
                if (coordinator.isDefaultCurrent(generation, revision)) ready.add(coordinator.defaultSkin());
            }
        });
        try {
            coordinator.setDefaultRegions(Set.of(HEAD, BODY));
            coordinator.onConfigReload(defaultConfig("Player"));
            assertEquals(original, ready.poll(5, TimeUnit.SECONDS));
            coordinator.setDefaultRegions(Set.of(HEAD, BODY));
            assertEquals(original, ready.poll(5, TimeUnit.SECONDS));
            try (var tasks = Executors.newVirtualThreadPerTaskExecutor(); var gate = new IoGate(cache)) {
                tasks.submit(() -> coordinator.setDefaultSource(source(UUID.randomUUID(), partialHash))).get(2, TimeUnit.SECONDS);
                tasks.submit(() -> coordinator.setDefaultSource(source(UUID.randomUUID(), completeHash))).get(2, TimeUnit.SECONDS);
                assertEquals(original, coordinator.defaultSkin());
            }
            awaitDefault(ready, replacement, original);
            assertEquals(completeHash, cache.loadDefault("Player", cache.epoch()).textureHash());
            coordinator.setDefaultSource(source(UUID.randomUUID(), partialHash));
            var publish = SkinBakeCoordinator.class.getDeclaredMethod("publishDefault", long.class, long.class, long.class);
            publish.setAccessible(true);
            publish.invoke(coordinator, 0L, field(coordinator, "defaultRevision"), cache.epoch());
            assertEquals(replacement, coordinator.defaultSkin());
            assertEquals(completeHash, cache.loadDefault("Player", cache.epoch()).textureHash());
            assertTrue(cache.save(partialHash, false, Map.of(BODY, "partial-body"), cache.epoch()));
            coordinator.setDefaultSource(source(UUID.randomUUID(), partialHash));
            awaitDefault(ready, Map.of(HEAD, "partial-head", BODY, "partial-body"), replacement);
            assertEquals(partialHash, cache.loadDefault("Player", cache.epoch()).textureHash());
        } finally { coordinator.cancelPendingBakes(); }
    }

    private SkinBakeCoordinator coordinator(SkinCache cache) {
        var accounts = new MinecraftAccountManager(new AccountCredentialStore(this.directory.resolve("unused-accounts.bin")), new MinecraftAccountClient());
        var client = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String url) { throw new AssertionError("Cache-only preparation must not download skins"); }
        };
        return new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), client, cache,
            new AccountBakeQueue(accounts, client), new MineSkinProvider(cache, new MineSkinClient()));
    }

    private static PlayerSkinSource source(UUID player, String hash) { return new PlayerSkinSource(player, "Player", hash, "https://textures.minecraft.net/texture/" + hash, false); }

    private static Config defaultConfig(String name) {
        var config = Config.createDefault();
        return new Config(config.schemaVersion(), config.menuPageSize(), config.mineSkinApiKey(), config.mineSkinPollIntervalSeconds(),
            config.mineSkinCacheRetentionDays(), config.mineSkinCacheMaxMiB(), config.maxActiveDisplayEntities(), name);
    }

    private static void awaitDefault(BlockingQueue<Map<PlayerSkinRegion, String>> ready,
                                     Map<PlayerSkinRegion, String> expected, Map<PlayerSkinRegion, String> fallback) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            var textures = ready.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(textures, "Replacement default skin did not become ready");
            if (expected.equals(textures)) return;
            assertEquals(fallback, textures);
        }
    }

    private static Object field(SkinBakeCoordinator coordinator, String name) throws Exception {
        var field = SkinBakeCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        synchronized (coordinator) { return field.get(coordinator); }
    }

    private static final class IoGate implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        private IoGate(SkinCache cache) throws Exception {
            var entered = new CountDownLatch(1);
            this.thread = Thread.ofPlatform().daemon().start(() -> {
                synchronized (cache) {
                    entered.countDown();
                    try {
                        if (!this.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("IO gate timed out");
                    } catch (InterruptedException exception) { throw new AssertionError(exception); }
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }

        @Override public void close() throws Exception {
            this.release.countDown();
            this.thread.join(5_000);
            assertFalse(this.thread.isAlive());
        }
    }
}
