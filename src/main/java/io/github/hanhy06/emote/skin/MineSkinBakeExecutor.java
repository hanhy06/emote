package io.github.hanhy06.emote.skin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

final class MineSkinBakeExecutor {
    private final Supplier<ExecutorService> executorFactory;
    private final Map<String, Object> pendingTasks = new HashMap<>();
    private ExecutorService executor;

    MineSkinBakeExecutor() {
        this(MineSkinBakeExecutor::createExecutor);
    }

    MineSkinBakeExecutor(Supplier<ExecutorService> executorFactory) {
        this.executorFactory = executorFactory;
    }

    synchronized boolean submit(String key, Runnable task) {
        if (this.pendingTasks.containsKey(key)) {
            return false;
        }

        Object taskToken = new Object();
        this.pendingTasks.put(key, taskToken);
        try {
            executor().execute(() -> {
                try {
                    task.run();
                } finally {
                    removePendingTask(key, taskToken);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            this.pendingTasks.remove(key, taskToken);
            throw exception;
        }
    }

    synchronized void cancelAll() {
        this.pendingTasks.clear();
        if (this.executor == null) {
            return;
        }

        this.executor.shutdownNow();
        this.executor = null;
    }

    private synchronized ExecutorService executor() {
        if (this.executor == null) {
            this.executor = this.executorFactory.get();
        }
        return this.executor;
    }

    private synchronized void removePendingTask(String key, Object taskToken) {
        this.pendingTasks.remove(key, taskToken);
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "emote-mineskin");
            thread.setDaemon(true);
            return thread;
        });
    }
}
