package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.PlayerSkinProvider;
import io.github.hanhy06.emote.skin.SkinBakeCoordinator;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import io.github.hanhy06.emote.skin.SkinCache;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinBakeCoordinatorTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);

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
        @Override public void cleanupCache(int retentionDays, int maximumMiB) {}
    }
}
