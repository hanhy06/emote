package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftAccountManagerTest {
    @TempDir Path directory;

    @Test void multipleAccountsReconnectInPlaceAndRemovalSurvivesRestart() throws Exception {
        var store = new AccountCredentialStore(directory.resolve("accounts.bin"), false, AccountCredentialStoreTest.KEY);
        var manager = new MinecraftAccountManager(store, new MinecraftAccountClient());
        manager.initialize();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try {
            manager.register(new MinecraftSession(a, "Alpha", "access", Long.MAX_VALUE), "refresh-a");
            manager.register(new MinecraftSession(b, "Beta", "access", Long.MAX_VALUE), "refresh-b");
            manager.register(new MinecraftSession(a, "Renamed", "access", Long.MAX_VALUE), "rotated-a");
            assertEquals(java.util.List.of(a, b), manager.accounts().stream().map(MinecraftAccountManager.Account::uuid).toList());
            assertEquals("rotated-a", store.load().getAsJsonArray("accounts").get(0).getAsJsonObject().get("refreshToken").getAsString());
            assertTrue(manager.remove("renamed"));
            manager.close();
            manager.initialize();
            assertEquals(java.util.List.of(b), manager.accounts().stream().map(MinecraftAccountManager.Account::uuid).toList());
            assertTrue(manager.remove(b.toString()));
            assertFalse(manager.hasAccounts());
        } finally {
            manager.close();
        }
    }

    @Test void unreadableStoreDoesNotFallBackOrGetOverwritten() throws Exception {
        Path path = directory.resolve("accounts.bin");
        byte[] damaged = {2, 0, 0};
        Files.write(path, damaged);
        var manager = new MinecraftAccountManager(new AccountCredentialStore(path, false, AccountCredentialStoreTest.KEY), new MinecraftAccountClient());
        manager.initialize();
        try {
            assertTrue(manager.hasAccounts());
            assertNotNull(manager.storageError());
            assertThrows(IOException.class, () -> manager.remove("Alpha"));
            assertArrayEquals(damaged, Files.readAllBytes(path));
        } finally {
            manager.close();
        }
    }

    @Test void refreshRotationIsSavedEvenWhenDownstreamAuthenticationFails() throws Exception {
        var store = new AccountCredentialStore(directory.resolve("accounts.bin"), false, AccountCredentialStoreTest.KEY);
        var client = new MinecraftAccountClient() {
            @Override public MicrosoftTokens refresh(String token) { return new MicrosoftTokens("new-access", "new-refresh"); }
            @Override public MinecraftSession authenticate(MicrosoftTokens tokens) throws IOException { throw new IOException("Xbox unavailable"); }
        };
        var manager = new MinecraftAccountManager(store, client);
        manager.initialize();
        try {
            manager.register(new MinecraftSession(UUID.randomUUID(), "Alpha", "expired", 0), "old-refresh");
            assertThrows(IOException.class, () -> manager.session(manager.accounts().getFirst()));
            assertEquals("new-refresh", store.load().getAsJsonArray("accounts").get(0).getAsJsonObject().get("refreshToken").getAsString());
        } finally {
            manager.close();
        }
    }
}
