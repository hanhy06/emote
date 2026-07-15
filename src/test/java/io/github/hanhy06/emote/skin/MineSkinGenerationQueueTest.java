package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MineSkinGenerationQueueTest {
    @Test
    void cancelAllInterruptsRunningTaskAndAllowsNewTasks() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            MineSkinGenerationQueue executor = new MineSkinGenerationQueue();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            executor.submit("skin", () -> {
                started.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                }
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.cancelAll();

            CountDownLatch restarted = new CountDownLatch(1);
            assertTrue(executor.submit("skin", restarted::countDown));
            assertTrue(restarted.await(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            executor.cancelAll();
        });
    }

    @Test
    void submitDeduplicatesPendingSkinKey() throws InterruptedException {
        MineSkinGenerationQueue executor = new MineSkinGenerationQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        assertTrue(executor.submit("skin", () -> {
            runCount.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertFalse(executor.submit("skin", runCount::incrementAndGet));
        release.countDown();
        executor.cancelAll();

        assertEquals(1, runCount.get());
    }
}
