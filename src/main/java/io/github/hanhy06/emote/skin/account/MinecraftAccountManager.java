package io.github.hanhy06.emote.skin.account;

import com.google.gson.*;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class MinecraftAccountManager {
    private final AccountCredentialStore store;
    private final MinecraftAccountClient client;
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private Future<?> pendingLogin;
    private long generation;
    private boolean initialized;
    private String storageError;

    public MinecraftAccountManager(AccountCredentialStore store, MinecraftAccountClient client) {
        this.store = store;
        this.client = client;
    }

    public synchronized void initialize() {
        if (this.initialized) return;
        this.initialized = true;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            JsonObject json = this.store.load();
            if (json.has("accounts")) {
                for (JsonElement element : json.getAsJsonArray("accounts")) {
                    JsonObject value = element.getAsJsonObject();
                    Account account = new Account(UUID.fromString(value.get("uuid").getAsString()), value.get("name").getAsString(), value.get("refreshToken").getAsString());
                    this.accounts.put(account.uuid, account);
                }
            }
        } catch (IOException | RuntimeException exception) {
            this.accounts.clear();
            this.storageError = "Cannot read encrypted account store; check the server identity or EMOTE_ACCOUNT_KEY";
        }
    }

    public synchronized boolean hasAccounts() {
        // A locked store must not silently switch providers or be overwritten as an empty store.
        return this.storageError != null || !this.accounts.isEmpty();
    }

    public synchronized String storageError() {
        return this.storageError;
    }

    public synchronized List<Account> accounts() {
        return List.copyOf(this.accounts.values());
    }

    public synchronized boolean contains(Account account) {
        return this.accounts.get(account.uuid) == account;
    }

    public void addChangeListener(Runnable listener) {
        this.changeListeners.add(listener);
    }

    public synchronized void login(Consumer<DeviceLogin> onCode, Consumer<String> onResult, BooleanSupplier authorized) throws IOException {
        requireStore();
        if (this.pendingLogin != null && !this.pendingLogin.isDone()) throw new IOException("An account login is already pending");
        long loginGeneration = this.generation;
        this.pendingLogin = this.executor.submit(() -> {
            try {
                DeviceLogin login = this.client.startLogin();
                onCode.accept(login);
                long interval = login.intervalSeconds() * 1000L;
                while (System.currentTimeMillis() < login.expiresAt()) {
                    Thread.sleep(interval);
                    if (System.currentTimeMillis() >= login.expiresAt()) break;
                    MicrosoftTokens tokens;
                    try {
                        tokens = this.client.poll(login);
                    } catch (AuthenticationException exception) {
                        if (exception.code.equals("authorization_pending")) continue;
                        if (exception.code.equals("slow_down")) {
                            interval += 5000;
                            continue;
                        }
                        throw exception;
                    }
                    MinecraftSession session = this.client.authenticate(tokens);
                    if (!authorized.getAsBoolean()) return;
                    synchronized (this) {
                        if (!this.initialized || loginGeneration != this.generation) return;
                        register(session, tokens.refreshToken());
                    }
                    onResult.accept("Connected bake account: " + session.name());
                    return;
                }
                onResult.accept("Account login expired; run /emote account login again");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                onResult.accept(exception.getMessage());
            } catch (RuntimeException exception) {
                onResult.accept("Account login failed; check the application registration and try again");
            }
        });
    }

    synchronized void register(MinecraftSession session, String refreshToken) throws IOException {
        requireStore();
        Account old = this.accounts.get(session.uuid());
        Account replacement = new Account(session.uuid(), session.name(), refreshToken);
        replacement.session = session;
        this.accounts.put(session.uuid(), replacement);
        try {
            save();
        } catch (IOException exception) {
            if (old == null) this.accounts.remove(session.uuid());
            else this.accounts.put(session.uuid(), old);
            throw exception;
        }
        this.changeListeners.forEach(this.executor::execute);
    }

    public synchronized boolean remove(String nameOrUuid) throws IOException {
        requireStore();
        Account removed = this.accounts.values().stream().filter(account -> account.uuid.toString().equalsIgnoreCase(nameOrUuid)
            || account.name.equalsIgnoreCase(nameOrUuid)).findFirst().orElse(null);
        if (removed == null) return false;
        Map<UUID, Account> previous = new LinkedHashMap<>(this.accounts);
        this.accounts.remove(removed.uuid);
        try {
            save();
        } catch (IOException exception) {
            this.accounts.clear();
            this.accounts.putAll(previous);
            throw exception;
        }
        this.generation++;
        if (this.pendingLogin != null) this.pendingLogin.cancel(true);
        removed.refreshToken = "";
        removed.session = null;
        this.changeListeners.forEach(this.executor::execute);
        return true;
    }

    public MinecraftSession session(Account account) throws IOException, InterruptedException {
        synchronized (account) {
            String refreshToken;
            synchronized (this) {
                if (!contains(account)) throw new IOException("Bake account was removed or reconnected");
                if (account.reauthenticationRequired) throw new IOException("Bake account requires /emote account login");
                if (account.session != null && account.session.expiresAt() > System.currentTimeMillis() + 60_000) return account.session;
                refreshToken = account.refreshToken;
            }
            try {
                MicrosoftTokens tokens = this.client.refresh(refreshToken);
                // Persist refresh-token rotation before the downstream Xbox/Minecraft exchange.
                synchronized (this) {
                    if (!contains(account)) throw new IOException("Bake account was removed or reconnected");
                    account.refreshToken = tokens.refreshToken();
                    save();
                }
                MinecraftSession session = this.client.authenticate(tokens);
                synchronized (this) {
                    if (!contains(account)) throw new IOException("Bake account was removed or reconnected");
                    if (!session.uuid().equals(account.uuid)) throw new IOException("Refreshed account identity does not match");
                    account.session = session;
                }
                return session;
            } catch (AuthenticationException exception) {
                if (exception.code.equals("invalid_grant")) account.reauthenticationRequired = true;
                throw exception;
            }
        }
    }

    public synchronized void invalidate(Account account) {
        account.session = null;
    }

    public synchronized void requireLogin(Account account) {
        account.session = null;
        account.reauthenticationRequired = true;
    }

    public synchronized void close() {
        this.generation++;
        this.initialized = false;
        if (this.executor != null) this.executor.shutdownNow();
        this.pendingLogin = null;
        for (Account account : this.accounts.values()) {
            account.refreshToken = "";
            account.session = null;
        }
        this.accounts.clear();
        this.storageError = null;
    }

    private void requireStore() throws IOException {
        if (!this.initialized) throw new IOException("Account manager is not running");
        if (this.storageError != null) throw new IOException(this.storageError);
    }

    private void save() throws IOException {
        JsonObject json = new JsonObject();
        JsonArray values = new JsonArray();
        for (Account account : this.accounts.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("uuid", account.uuid.toString());
            value.addProperty("name", account.name);
            value.addProperty("refreshToken", account.refreshToken);
            values.add(value);
        }
        json.add("accounts", values);
        this.store.save(json);
    }

    public static final class Account {
        private final UUID uuid;
        private final String name;
        private String refreshToken;
        private volatile MinecraftSession session;
        private volatile boolean reauthenticationRequired;

        private Account(UUID uuid, String name, String refreshToken) {
            this.uuid = uuid;
            this.name = name;
            this.refreshToken = refreshToken;
        }

        public UUID uuid() { return this.uuid; }
        public String name() { return this.name; }
        public boolean needsLogin() { return this.reauthenticationRequired; }
    }
}
