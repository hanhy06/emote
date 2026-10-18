package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.account.*;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.SkinOverride;
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
    void coalescesEqualOverridesButKeepsTextureMaskAndOriginalCachesSeparate() throws Exception {
        var cache = new SkinCache(this.directory);
        var original = source(UUID.randomUUID(), HASH);
        var first = original.withOverride(new SkinOverride(png(0xff123456), png(0xffffffff)));
        var same = source(UUID.randomUUID(), HASH).withOverride(new SkinOverride(png(0xff123456), png(0xffffffff)));
        var textureChanged = original.withOverride(new SkinOverride(png(0xffabcdef), png(0xffffffff)));
        var maskChanged = original.withOverride(new SkinOverride(png(0xff123456), png(0xff000000)));
        assertEquals(first.textureHash(), same.textureHash());
        assertEquals(4, Set.of(original.textureHash(), first.textureHash(), textureChanged.textureHash(), maskChanged.textureHash()).size());
        assertTrue(cache.save(original.textureHash(), false, Map.of(HEAD, "original"), cache.epoch()));
        assertTrue(cache.save(first.textureHash(), false, Map.of(HEAD, "first"), cache.epoch()));
        assertTrue(cache.save(textureChanged.textureHash(), false, Map.of(HEAD, "texture"), cache.epoch()));
        assertTrue(cache.save(maskChanged.textureHash(), false, Map.of(HEAD, "mask"), cache.epoch()));
        cache.clearMemory();
        var coordinator = coordinator(cache);
        var ready = new LinkedBlockingQueue<UUID>();
        coordinator.setListener(new SkinBakeCoordinator.Listener() {
            public void onReady(UUID player, long generation) { ready.add(player); }
        });
        try {
            try (var gate = new IoGate(cache)) {
                coordinator.prepare(first, Set.of(HEAD));
                coordinator.prepare(same, Set.of(HEAD));
                coordinator.prepare(textureChanged, Set.of(HEAD));
                coordinator.prepare(maskChanged, Set.of(HEAD));
                coordinator.prepare(original, Set.of(HEAD));
                var stats = coordinator.processingStats();
                assertEquals(4, stats.activeJobs() + stats.queuedJobs());
                assertTrue(ready.isEmpty());
            }
            var subscribers = new java.util.ArrayList<UUID>();
            for (int i = 0; i < 5; i++) subscribers.add(ready.poll(5, TimeUnit.SECONDS));
            assertEquals(4, java.util.Collections.frequency(subscribers, original.playerUuid()));
            assertEquals(1, java.util.Collections.frequency(subscribers, same.playerUuid()));
            assertEquals(Map.of(HEAD, "first"), coordinator.prepare(first, Set.of(HEAD)).textures());
            assertEquals(Map.of(HEAD, "texture"), coordinator.prepare(textureChanged, Set.of(HEAD)).textures());
            assertEquals(Map.of(HEAD, "mask"), coordinator.prepare(maskChanged, Set.of(HEAD)).textures());
            assertEquals(Map.of(HEAD, "original"), coordinator.prepare(original, Set.of(HEAD)).textures());
        } finally { coordinator.cancelPendingBakes(); }
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, color);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
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
