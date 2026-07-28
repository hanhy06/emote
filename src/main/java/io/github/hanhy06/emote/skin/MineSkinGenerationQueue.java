package io.github.hanhy06.emote.skin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class MineSkinGenerationQueue {
    private final Supplier<ExecutorService> executorFactory;
    private final Map<String, PendingTask> pendingTasks = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private long generation;

    MineSkinGenerationQueue() {
        this(MineSkinGenerationQueue::createExecutor);
    }

    MineSkinGenerationQueue(Supplier<ExecutorService> executorFactory) {
        this.executorFactory = executorFactory;
    }

    synchronized boolean submit(String key, Runnable task) {
        PendingTask pendingTask = this.pendingTasks.get(key);
        if (pendingTask != null) {
            pendingTask.requestRerun();
            return false;
        }
        PendingTask newTask = new PendingTask(task);
        long taskGeneration = this.generation;
        this.pendingTasks.put(key, newTask);
        try {
            ensureExecutor();
            this.executor.execute(() -> {
                while (true) {
                    newTask.task().run();
                    synchronized (MineSkinGenerationQueue.this) {
                        if (taskGeneration != generation) {
                            pendingTasks.remove(key, newTask);
                            return;
                        }
                        if (newTask.takeRerunRequest()) {
                            continue;
                        }
                        pendingTasks.remove(key, newTask);
                        return;
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            this.pendingTasks.remove(key, newTask);
            throw exception;
        }
    }

    synchronized void cancelAll() {
        this.generation++;
        this.pendingTasks.clear();
        for (ScheduledFuture<?> scheduledTask : this.scheduledTasks.values()) {
            scheduledTask.cancel(true);
        }
        this.scheduledTasks.clear();
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
            this.scheduler = null;
        }
    }

    synchronized boolean schedule(String key, Runnable task, long delayMillis) {
        if (this.scheduledTasks.containsKey(key)) {
            return false;
        }
        long scheduledGeneration = this.generation;
        ensureScheduler();
        ScheduledFuture<?> scheduledTask = this.scheduler.schedule(() -> {
            synchronized (MineSkinGenerationQueue.this) {
                if (scheduledGeneration != generation) {
                    return;
                }
                scheduledTasks.remove(key);
                submit(key, task);
            }
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
        this.scheduledTasks.put(key, scheduledTask);
        return true;
    }

    synchronized void cancelScheduled(String key) {
        ScheduledFuture<?> scheduledTask = this.scheduledTasks.remove(key);
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
    }

    private void ensureExecutor() {
        if (this.executor == null) {
            this.executor = this.executorFactory.get();
        }
    }

    private void ensureScheduler() {
        if (this.scheduler == null) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "emote-mineskin-retry");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "emote-mineskin");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class PendingTask {
        private final Runnable task;
        private boolean rerunRequested;

        private PendingTask(Runnable task) {
            this.task = task;
        }

        private Runnable task() {
            return this.task;
        }

        private void requestRerun() {
            this.rerunRequested = true;
        }

        private boolean takeRerunRequest() {
            boolean requested = this.rerunRequested;
            this.rerunRequested = false;
            return requested;
        }
    }
}
