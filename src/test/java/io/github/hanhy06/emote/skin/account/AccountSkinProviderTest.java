package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.skin.*;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import io.github.hanhy06.emote.skin.mineskin.*;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountSkinProviderTest {
    @TempDir Path directory;

    @Test void identicalContentIsUploadedOnceAndMineSkinReusesTheCompletedCache() throws Exception {
        var accounts = new MinecraftAccountManager(new AccountCredentialStore(directory.resolve("accounts.bin"), false, AccountCredentialStoreTest.KEY), new MinecraftAccountClient());
        accounts.initialize();
        accounts.register(new MinecraftSession(UUID.randomUUID(), "Baker", "access", Long.MAX_VALUE), "refresh");
        CountDownLatch bothDownloads = new CountDownLatch(2);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        AtomicInteger uploads = new AtomicInteger();
        var client = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String url) throws InterruptedException {
                bothDownloads.countDown();
                assertTrue(bothDownloads.await(5, TimeUnit.SECONDS));
                return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            }
            @Override public String upload(MinecraftSession session, byte[] png, boolean slim) throws InterruptedException {
                uploads.incrementAndGet();
                uploadStarted.countDown();
                assertTrue(releaseUpload.await(5, TimeUnit.SECONDS));
                return "https://textures.minecraft.net/texture/abc";
            }
        };
        var cache = new MineSkinCache(directory.resolve("skins"));
        var queue = new AccountBakeQueue(accounts, client, (png, slim) -> { throw new AssertionError("Unexpected fallback"); });
        var provider = new AccountSkinProvider(accounts, new PlayerSkinBaker(), client, cache, queue);
        provider.setListener(new PlayerSkinProvider.Listener() {
            @Override public void onReady(UUID uuid) { ready.countDown(); }
        });
        var a = new PlayerSkinSource(UUID.randomUUID(), "Alpha", "aaa", "https://example.invalid/a", false);
        var b = new PlayerSkinSource(UUID.randomUUID(), "Beta", "bbb", "https://example.invalid/b", false);
        var region = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        try {
            assertEquals(PlayerSkinPreparation.State.PREPARING, provider.prepare(a, Set.of(region)).state());
            assertEquals(PlayerSkinPreparation.State.PREPARING, provider.prepare(b, Set.of(region)).state());
            assertTrue(uploadStarted.await(5, TimeUnit.SECONDS));
            releaseUpload.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(1, uploads.get());
            assertEquals(PlayerSkinPreparation.State.READY, provider.prepare(a, Set.of(region)).state());
            accounts.remove("Baker");
            var mineSkin = new MineSkinProvider(new PlayerSkinBaker(), cache, new MineSkinClient(), new MineSkinTaskQueue());
            var selected = new AutomaticSkinProvider(accounts::hasAccounts, provider, mineSkin);
            assertEquals(PlayerSkinPreparation.State.READY, selected.prepare(a, Set.of(region)).state());
            assertEquals(PlayerSkinPreparation.State.READY, selected.prepare(b, Set.of(region)).state());
            assertEquals(1, uploads.get());
        } finally {
            releaseUpload.countDown();
            provider.cancelPendingBakes();
            accounts.close();
        }
    }

    @Test void concurrentPartSavesMergeInsteadOfLosingEarlierParts() throws Exception {
        var cache = new MineSkinCache(directory.resolve("skins"));
        var head = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
        var body = new PlayerSkinRegion(PlayerSkinPart.BODY, PlayerSkinSegment.FULL);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> cache.save("aaa", false, Map.of(head, "head")));
            var b = workers.submit(() -> cache.save("aaa", false, Map.of(body, "body")));
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        assertEquals(Map.of(head, "head", body, "body"), new MineSkinCache(directory.resolve("skins")).load("aaa", false));
    }
}
