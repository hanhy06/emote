package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.skin.account.MinecraftAccountManager.Account;
import io.github.hanhy06.emote.skin.account.MinecraftSkinClient.SkinRequestException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Round-robin assignment with one serial worker per Minecraft UUID. */
public final class AccountBakeQueue {
    private final MinecraftAccountManager accounts;
    private final MinecraftSkinClient client;
    private final Upload fallback;
    private final Map<UUID, Worker> workers = new HashMap<>();
    private ExecutorService executor;
    private long nextAccount;
    private long generation;

    public AccountBakeQueue(MinecraftAccountManager accounts, MinecraftSkinClient client, Upload fallback) {
        this.accounts = accounts;
        this.client = client;
        this.fallback = fallback;
        accounts.addChangeListener(this::redistributePending);
    }

    public synchronized CompletableFuture<String> submit(byte[] png, boolean slim) {
        Task task = new Task(png, slim, this.generation);
        if (this.executor == null) this.executor = Executors.newVirtualThreadPerTaskExecutor();
        assign(task);
        return task.result;
    }

    private void assign(Task task) {
        if (task.generation != this.generation || task.result.isDone()) return;
        List<Account> registered = this.accounts.accounts();
        Account selected = null;
        if (!registered.isEmpty()) {
            for (int offset = 0; offset < registered.size(); offset++) {
                int index = (int) Math.floorMod(this.nextAccount++, registered.size());
                Account candidate = registered.get(index);
                if (!candidate.needsLogin()) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null && this.accounts.hasAccounts()) {
            task.result.completeExceptionally(new IOException("No usable bake account; run /emote account login"));
            return;
        }
        task.account = selected;
        // A null UUID is the single serial MineSkin worker for tasks displaced by the last removal.
        UUID key = selected == null ? null : selected.uuid();
        Worker worker = this.workers.computeIfAbsent(key, ignored -> new Worker());
        worker.pending.addLast(task);
        if (!worker.running) {
            worker.running = true;
            this.executor.execute(() -> drain(key, worker, task.generation));
        }
    }

    private void drain(UUID key, Worker worker, long workerGeneration) {
        while (true) {
            Task task;
            synchronized (this) {
                if (workerGeneration != this.generation) return;
                task = worker.pending.pollFirst();
                if (task == null) {
                    this.workers.remove(key, worker);
                    return;
                }
                if (task.account != null && (!this.accounts.contains(task.account) || task.account.needsLogin())) {
                    assign(task);
                    continue;
                }
                worker.active = task;
            }
            try {
                String url = task.account == null ? this.fallback.upload(task.png, task.slim) : upload(task);
                task.result.complete(url);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                task.result.cancel(false);
                return;
            } catch (IOException | RuntimeException exception) {
                task.result.completeExceptionally(exception);
            } finally {
                synchronized (this) { worker.active = null; }
            }
        }
    }

    private String upload(Task task) throws IOException, InterruptedException {
        boolean refreshed = false;
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                return this.client.upload(this.accounts.session(task.account), task.png, task.slim);
            } catch (SkinRequestException exception) {
                if (exception.status == 401) {
                    if (refreshed) {
                        this.accounts.requireLogin(task.account);
                        throw exception;
                    }
                    this.accounts.invalidate(task.account);
                    refreshed = true;
                } else if (attempt < 2 && (exception.status == 429 || exception.status >= 500)) {
                    Thread.sleep(exception.status == 429 ? exception.retryDelayMillis : 2000);
                } else {
                    throw exception;
                }
            }
        }
    }

    private synchronized void redistributePending() {
        List<Task> displaced = new ArrayList<>();
        for (Worker worker : this.workers.values()) {
            worker.pending.removeIf(task -> {
                if (task.account != null && !this.accounts.contains(task.account)) {
                    displaced.add(task);
                    return true;
                }
                return false;
            });
        }
        displaced.forEach(this::assign);
    }

    public void cancelAll() {
        List<Task> canceled = new ArrayList<>();
        synchronized (this) {
            this.generation++;
            for (Worker worker : this.workers.values()) {
                if (worker.active != null) canceled.add(worker.active);
                canceled.addAll(worker.pending);
            }
            this.workers.clear();
            if (this.executor != null) this.executor.shutdownNow();
            this.executor = null;
            this.nextAccount = 0;
        }
        canceled.forEach(task -> task.result.cancel(false));
    }

    @FunctionalInterface public interface Upload {
        String upload(byte[] png, boolean slim) throws IOException, InterruptedException;
    }

    private static final class Worker {
        final Deque<Task> pending = new ArrayDeque<>();
        Task active;
        boolean running;
    }

    private static final class Task {
        final byte[] png;
        final boolean slim;
        final long generation;
        final CompletableFuture<String> result = new CompletableFuture<>();
        Account account;

        Task(byte[] png, boolean slim, long generation) {
            this.png = png;
            this.slim = slim;
            this.generation = generation;
        }
    }
}
