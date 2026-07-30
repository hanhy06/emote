package io.github.hanhy06.emote.skin;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MineSkinGenerationQueueTest {
    @Test
    void failedTaskReleasesPendingKey() throws InterruptedException {
        CountDownLatch failureObserved = new CountDownLatch(1);
        ExecutorService executorService = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setUncaughtExceptionHandler((ignoredThread, ignoredException) -> failureObserved.countDown());
            return thread;
        });
        MineSkinGenerationQueue queue = new MineSkinGenerationQueue(() -> executorService);

        assertTrue(queue.submit("skin", () -> {
            throw new IllegalStateException("failed");
        }));
        assertTrue(failureObserved.await(1, TimeUnit.SECONDS));

        CountDownLatch restarted = new CountDownLatch(1);
        assertTrue(queue.submit("skin", restarted::countDown));
        assertTrue(restarted.await(1, TimeUnit.SECONDS));
        queue.cancelAll();
    }

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
    void submitRequestsOneRerunForPendingSkinKey() throws InterruptedException {
        MineSkinGenerationQueue executor = new MineSkinGenerationQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reran = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        assertTrue(executor.submit("skin", () -> {
            if (runCount.incrementAndGet() == 2) {
                reran.countDown();
            }
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
        assertTrue(reran.await(1, TimeUnit.SECONDS));
        executor.cancelAll();

        assertEquals(2, runCount.get());
    }

    @Test
    void scheduledRetryDoesNotOccupyWorker() throws InterruptedException {
        MineSkinGenerationQueue executor = new MineSkinGenerationQueue();
        CountDownLatch workerRan = new CountDownLatch(1);
        CountDownLatch retryRan = new CountDownLatch(1);

        assertTrue(executor.schedule("retry", retryRan::countDown, 100L));
        assertTrue(executor.submit("other", workerRan::countDown));

        assertTrue(workerRan.await(1, TimeUnit.SECONDS));
        assertTrue(retryRan.await(1, TimeUnit.SECONDS));
        executor.cancelAll();
    }

    @Test
    void cancelAllDropsRequestedRerunForRunningTask() throws InterruptedException {
        MineSkinGenerationQueue executor = new MineSkinGenerationQueue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch reran = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        executor.submit("skin", () -> {
            if (runCount.incrementAndGet() > 1) {
                reran.countDown();
            }
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertFalse(executor.submit("skin", () -> {}));

        executor.cancelAll();

        assertFalse(reran.await(200, TimeUnit.MILLISECONDS));
        assertEquals(1, runCount.get());
    }
}
