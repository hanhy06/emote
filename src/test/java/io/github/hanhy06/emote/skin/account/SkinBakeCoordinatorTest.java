package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.PlayerSkinProvider;
import io.github.hanhy06.emote.skin.SkinBakeCoordinator;
import io.github.hanhy06.emote.skin.SkinCache;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SkinBakeCoordinatorTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);

    @Test
    void defaultSkinPersistsEachSharedBakeResultAndReloadRetriesMissingRegion(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        SkinCache cache = new SkinCache(tempDir.resolve("skin"));
        PlayerSkinRegion body = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String textureUrl) {
                BufferedImage image = opaqueSkin();
                for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, 0xFF0000FF);
                return image;
            }
        };
        AtomicInteger uploads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        SkinBakeCoordinator.FallbackUploader uploader = new SkinBakeCoordinator.FallbackUploader() {
            @Override public void configure(Config config) {}
            @Override public boolean available() { return true; }
            @Override public String upload(byte[] png, boolean slim) throws java.io.IOException {
                int count = uploads.incrementAndGet();
                if (count > 1 && fail.get()) throw new java.io.IOException("second region API failure");
                return "texture-" + count;
            }
        };
        SkinBakeCoordinator coordinator = new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), skinClient, cache,
            new AccountBakeQueue(accounts, skinClient), uploader);
        try {
            coordinator.setDefaultRegions(Set.of(HEAD, body));
            coordinator.onConfigReload(defaultConfig("Player"));
            coordinator.setDefaultSource(source(UUID.randomUUID(), "shared"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.processingStats().retryingJobs() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, coordinator.processingStats().retryingJobs());
            assertEquals(1, cache.loadDefault("Player").textures().size());
            assertEquals(cache.load("shared", false), cache.loadDefault("Player").textures());
            assertEquals(1, coordinator.defaultSkin().textureUrlMap().size());
            fail.set(false);
            coordinator.onConfigReload(defaultConfig("Player"));
            coordinator.setDefaultSource(source(UUID.randomUUID(), "shared"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.defaultSkin().textureUrlMap().size() != 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, coordinator.defaultSkin().textureUrlMap().size());
            assertEquals(2, new SkinCache(tempDir.resolve("skin")).loadDefault("Player").textures().size());
            assertEquals(3, uploads.get());
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void freshDefaultSkinIsBakedAndSavedAtStartup(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        SkinCache cache = new SkinCache(tempDir.resolve("skin"));
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String textureUrl) { return opaqueSkin(); }
        };
        RecordingFallback fallback = new RecordingFallback();
        SkinBakeCoordinator coordinator = new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), skinClient, cache,
            new AccountBakeQueue(accounts, skinClient), fallback);
        CountDownLatch ready = new CountDownLatch(1);
        coordinator.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onDefaultReady() { ready.countDown(); }
        });
        try {
            coordinator.onConfigReload(defaultConfig("Player"));
            coordinator.setDefaultSource(source(UUID.randomUUID(), "fresh"));
            coordinator.setDefaultRegions(Set.of(HEAD));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals("fallback-texture", coordinator.defaultSkin().findTextureUrl(HEAD));
            assertEquals("fresh", new SkinCache(tempDir.resolve("skin")).loadDefault("Player").textureHash());
            assertEquals(1, fallback.uploads.get());
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void defaultSkinUsesPinnedCacheWithoutLookupAndBakesNewRegions(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        SkinCache cache = new SkinCache(tempDir.resolve("skin"));
        cache.saveDefault(new SkinCache.DefaultSkin("Player", "saved", "https://textures.example/saved", false,
            java.util.Map.of(HEAD, "saved-head")));
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String textureUrl) { return opaqueSkin(); }
        };
        RecordingFallback fallback = new RecordingFallback();
        SkinBakeCoordinator coordinator = new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), skinClient, cache,
            new AccountBakeQueue(accounts, skinClient), fallback);
        CountDownLatch ready = new CountDownLatch(1);
        coordinator.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onDefaultReady() { ready.countDown(); }
        });
        try {
            coordinator.setDefaultRegions(Set.of(HEAD));
            coordinator.onConfigReload(defaultConfig("Player"));
            assertEquals("saved-head", coordinator.defaultSkin().findTextureUrl(HEAD));
            assertEquals(0, fallback.uploads.get());
            PlayerSkinRegion upper = new PlayerSkinRegion(PlayerSkinPart.LEFT_ARM, new PlayerSkinSegment(0, 4));
            coordinator.setDefaultRegions(Set.of(HEAD, upper));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals("saved-head", coordinator.defaultSkin().findTextureUrl(HEAD));
            assertEquals("fallback-texture", coordinator.defaultSkin().findTextureUrl(upper));
            assertEquals(1, fallback.uploads.get());
            assertEquals(coordinator.defaultSkin().textureUrlMap(), new SkinCache(tempDir.resolve("skin")).loadDefault("Player").textures());
            coordinator.onConfigReload(defaultConfig("Other"));
            assertNull(coordinator.defaultSkin());
            coordinator.onConfigReload(defaultConfig(""));
            assertNull(coordinator.defaultSkin());
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void failedDefaultRefreshKeepsPreviousPinnedSkin(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        SkinCache cache = new SkinCache(tempDir.resolve("skin"));
        cache.saveDefault(new SkinCache.DefaultSkin("Player", "saved", "https://textures.example/saved", false,
            java.util.Map.of(HEAD, "saved-head")));
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String textureUrl) throws java.io.IOException {
                throw new java.io.IOException("API unavailable");
            }
        };
        SkinBakeCoordinator coordinator = new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), skinClient, cache,
            new AccountBakeQueue(accounts, skinClient), new RecordingFallback());
        try {
            coordinator.setDefaultRegions(Set.of(HEAD));
            coordinator.onConfigReload(defaultConfig("Player"));
            coordinator.setDefaultSource(source(UUID.randomUUID(), "changed"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.processingStats().retryingJobs() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, coordinator.processingStats().retryingJobs());
            assertEquals("saved-head", coordinator.defaultSkin().findTextureUrl(HEAD));
            assertEquals("saved", cache.loadDefault("Player").textureHash());
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    private static Config defaultConfig(String name) {
        Config defaults = Config.createDefault();
        return new Config(defaults.schemaVersion(), defaults.menuPageSize(), defaults.mineSkinApiKey(),
            defaults.mineSkinPollIntervalSeconds(), defaults.mineSkinCacheRetentionDays(), defaults.mineSkinCacheMaxMiB(),
            defaults.maxActiveDisplayEntities(), name);
    }

    @Test
    void mergesSubscribersIntoOneBakeAndOneUpload(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override
            public BufferedImage downloadSkin(String textureUrl) throws InterruptedException {
                downloadStarted.countDown();
                releaseDownload.await();
                return opaqueSkin();
            }
        };
        RecordingFallback fallback = new RecordingFallback();
        SkinBakeCoordinator coordinator = coordinator(tempDir, accounts, skinClient, fallback);
        CountDownLatch ready = new CountDownLatch(2);
        coordinator.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onReady(UUID playerUuid) { ready.countDown(); }
        });
        PlayerSkinSource first = source(UUID.randomUUID(), "same-hash");
        PlayerSkinSource second = source(UUID.randomUUID(), "same-hash");

        try {
            assertEquals(PlayerSkinPreparation.State.PREPARING, coordinator.prepare(first, Set.of(HEAD)).state());
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS));
            assertEquals(PlayerSkinPreparation.State.PREPARING, coordinator.prepare(second, Set.of(HEAD)).state());
            releaseDownload.countDown();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(1, fallback.uploads.get());
            assertEquals(PlayerSkinPreparation.State.READY, coordinator.prepare(first, Set.of(HEAD)).state());
        } finally {
            releaseDownload.countDown();
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void newWorkUsesFallbackAfterLastAccountIsRemoved(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        UUID accountId = UUID.randomUUID();
        accounts.register(new MinecraftSession(accountId, "Baker", "access", Long.MAX_VALUE), "refresh");
        AtomicInteger accountUploads = new AtomicInteger();
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String textureUrl) {
                return opaqueSkin(textureUrl.contains("fallback-hash") ? 0xFF0000FF : 0xFFFF0000);
            }
            @Override public String upload(MinecraftSession session, byte[] png, boolean slim) {
                accountUploads.incrementAndGet();
                return "account-texture";
            }
        };
        RecordingFallback fallback = new RecordingFallback();
        SkinBakeCoordinator coordinator = coordinator(tempDir, accounts, skinClient, fallback);
        CountDownLatch ready = new CountDownLatch(2);
        coordinator.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onReady(UUID playerUuid) { ready.countDown(); }
        });

        try {
            coordinator.prepare(source(UUID.randomUUID(), "account-hash"), Set.of(HEAD));
            awaitCount(accountUploads, 1);
            accounts.remove(accountId.toString());
            coordinator.prepare(source(UUID.randomUUID(), "fallback-hash"), Set.of(HEAD));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(1, accountUploads.get());
            assertEquals(1, fallback.uploads.get());
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void cancellationSuppressesCompletionFromOldGeneration(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        MinecraftSkinClient skinClient = new MinecraftSkinClient() {
            @Override
            public BufferedImage downloadSkin(String textureUrl) throws InterruptedException {
                downloadStarted.countDown();
                releaseDownload.await();
                return opaqueSkin();
            }
        };
        SkinBakeCoordinator coordinator = coordinator(tempDir, accounts, skinClient, new RecordingFallback());
        AtomicInteger notifications = new AtomicInteger();
        coordinator.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onReady(UUID playerUuid) { notifications.incrementAndGet(); }
            @Override public void onFailed(UUID playerUuid) { notifications.incrementAndGet(); }
        });

        try {
            coordinator.prepare(source(UUID.randomUUID(), "cancelled"), Set.of(HEAD));
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS));
            coordinator.cancelPendingBakes();
            releaseDownload.countDown();
            Thread.sleep(100L);

            assertEquals(0, notifications.get());
            assertEquals(0, coordinator.processingStats().activeJobs());
        } finally {
            releaseDownload.countDown();
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test
    void configReloadCleansTheSharedSkinCache(@TempDir Path tempDir) throws Exception {
        MinecraftAccountManager accounts = accountManager(tempDir);
        MinecraftSkinClient skinClient = new MinecraftSkinClient();
        Path skinDirectory = tempDir.resolve("skin");
        SkinCache cache = new SkinCache(skinDirectory);
        String contentHash = SkinCache.createContentKey(new byte[] {1, 2, 3}, false);
        Path expiredFailure = skinDirectory.resolve("failures").resolve(contentHash + ".json");
        cache.saveFailure(contentHash, "expired", 1L);
        SkinBakeCoordinator coordinator = new SkinBakeCoordinator(
            accounts,
            new PlayerSkinBaker(),
            skinClient,
            cache,
            new AccountBakeQueue(accounts, skinClient),
            new RecordingFallback()
        );

        try {
            coordinator.onConfigReload(Config.createDefault());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.exists(expiredFailure) && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertFalse(Files.exists(expiredFailure));
        } finally {
            coordinator.cancelPendingBakes();
            accounts.close();
        }
    }

    private static SkinBakeCoordinator coordinator(
        Path tempDir,
        MinecraftAccountManager accounts,
        MinecraftSkinClient skinClient,
        SkinBakeCoordinator.FallbackUploader fallback
    ) {
        SkinCache cache = new SkinCache(tempDir.resolve("skin"));
        return new SkinBakeCoordinator(
            accounts,
            new PlayerSkinBaker(),
            skinClient,
            cache,
            new AccountBakeQueue(accounts, skinClient),
            fallback
        );
    }

    private static MinecraftAccountManager accountManager(Path tempDir) {
        MinecraftAccountManager manager = new MinecraftAccountManager(
            new AccountCredentialStore(tempDir.resolve("accounts.bin"), false, AccountCredentialStoreTest.KEY),
            new MinecraftAccountClient()
        );
        manager.initialize();
        return manager;
    }

    private static PlayerSkinSource source(UUID playerUuid, String textureHash) {
        return new PlayerSkinSource(playerUuid, "Player", textureHash, "https://textures.example/" + textureHash, false);
    }

    private static BufferedImage opaqueSkin() {
        return opaqueSkin(0xFFFF0000);
    }

    private static BufferedImage opaqueSkin(int color) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color);
            }
        }
        return image;
    }

    private static void awaitCount(AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (value.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, value.get());
    }

    private static final class RecordingFallback implements SkinBakeCoordinator.FallbackUploader {
        private final AtomicInteger uploads = new AtomicInteger();

        @Override public void configure(Config config) {}
        @Override public boolean available() { return true; }
        @Override public String upload(byte[] png, boolean slimModel) {
            this.uploads.incrementAndGet();
            return "fallback-texture";
        }
    }
}
