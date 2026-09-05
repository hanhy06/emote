package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountBakeQueueTest {
    @TempDir Path directory;

    @Test void assignsPartsRoundRobinAndSerializesEachAccount() throws Exception {
        var accounts = manager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        accounts.register(new MinecraftSession(a, "Alpha", "test", Long.MAX_VALUE), "refresh-a");
        accounts.register(new MinecraftSession(b, "Beta", "test", Long.MAX_VALUE), "refresh-b");
        Map<UUID, AtomicInteger> active = new ConcurrentHashMap<>();
        Map<Integer, UUID> assignments = new ConcurrentHashMap<>();
        CountDownLatch firstPair = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger collisions = new AtomicInteger();
        var queue = new AccountBakeQueue(accounts, new MinecraftSkinClient() {
            @Override public String upload(MinecraftSession session, byte[] png, boolean slim) throws InterruptedException {
                AtomicInteger running = active.computeIfAbsent(session.uuid(), ignored -> new AtomicInteger());
                if (running.incrementAndGet() != 1) collisions.incrementAndGet();
                try {
                    assignments.put((int) png[0], session.uuid());
                    if (png[0] < 2) {
                        firstPair.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return "texture-" + png[0];
                } finally { running.decrementAndGet(); }
            }
        }, (png, slim) -> { throw new AssertionError("Unexpected MineSkin fallback"); });
        try {
            List<CompletableFuture<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) jobs.add(queue.submit(new byte[]{(byte) i}, false));
            assertTrue(firstPair.await(5, TimeUnit.SECONDS), "Accounts should work concurrently");
            assertEquals(2, assignments.size(), "Queued work must not overlap on the same account");
            release.countDown();
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 6; i++) assertEquals(i % 2 == 0 ? a : b, assignments.get(i));
            assertEquals(0, collisions.get());
        } finally {
            release.countDown();
            queue.cancelAll();
            accounts.close();
        }
    }

    @Test void lastRemovalMovesUnstartedWorkToMineSkinWithoutWaitingForActiveUpload() throws Exception {
        var accounts = manager();
        UUID a = UUID.randomUUID();
        accounts.register(new MinecraftSession(a, "Alpha", "test", Long.MAX_VALUE), "refresh-a");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var queue = new AccountBakeQueue(accounts, new MinecraftSkinClient() {
            @Override public String upload(MinecraftSession session, byte[] png, boolean slim) throws InterruptedException {
                started.countDown();
                release.await();
                return "account";
            }
        }, (png, slim) -> "mineskin");
        try {
            var first = queue.submit(new byte[]{1}, false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = queue.submit(new byte[]{2}, false);
            accounts.remove(a.toString());
            assertEquals("mineskin", second.get(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            release.countDown();
            assertEquals("account", first.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            queue.cancelAll();
            accounts.close();
        }
    }

    @Test void unusableRegisteredAccountsDoNotTriggerFallback() throws Exception {
        var accounts = manager();
        accounts.register(new MinecraftSession(UUID.randomUUID(), "Alpha", "test", Long.MAX_VALUE), "refresh-a");
        accounts.requireLogin(accounts.accounts().getFirst());
        var queue = new AccountBakeQueue(accounts, new MinecraftSkinClient(), (png, slim) -> { throw new AssertionError("Unexpected fallback"); });
        try {
            assertThrows(ExecutionException.class, () -> queue.submit(new byte[]{1}, false).get(5, TimeUnit.SECONDS));
        } finally {
            queue.cancelAll();
            accounts.close();
        }
    }

    @Test void shutdownCancelsActiveAndQueuedResults() throws Exception {
        var accounts = manager();
        accounts.register(new MinecraftSession(UUID.randomUUID(), "Alpha", "test", Long.MAX_VALUE), "refresh-a");
        CountDownLatch started = new CountDownLatch(1);
        var queue = new AccountBakeQueue(accounts, new MinecraftSkinClient() {
            @Override public String upload(MinecraftSession session, byte[] png, boolean slim) throws InterruptedException {
                started.countDown();
                new CountDownLatch(1).await();
                return "never";
            }
        }, (png, slim) -> "fallback");
        try {
            var first = queue.submit(new byte[]{1}, false);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = queue.submit(new byte[]{2}, false);
            queue.cancelAll();
            assertTrue(first.isCancelled());
            assertTrue(second.isCancelled());
        } finally {
            queue.cancelAll();
            accounts.close();
        }
    }

    private MinecraftAccountManager manager() {
        var manager = new MinecraftAccountManager(new AccountCredentialStore(directory.resolve("accounts.bin"), false, AccountCredentialStoreTest.KEY), new MinecraftAccountClient(""));
        manager.initialize();
        return manager;
    }
}
