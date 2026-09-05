package io.github.hanhy06.emote.skin.account;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AccountCredentialStoreTest {
    @TempDir Path directory;
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void encryptedRoundTripRejectsTamperingAndWrongKey() throws Exception {
        Path path = directory.resolve("accounts.bin");
        var store = new AccountCredentialStore(path, false, KEY);
        var json = new JsonObject();
        json.addProperty("refreshToken", "test-secret-refresh-token");
        store.save(json);
        byte[] first = Files.readAllBytes(path);
        assertFalse(new String(first, StandardCharsets.UTF_8).contains("test-secret-refresh-token"));
        assertEquals(json, store.load());
        store.save(json);
        assertFalse(java.util.Arrays.equals(first, Files.readAllBytes(path)), "Each encryption needs a fresh nonce");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThrows(IOException.class, () -> new AccountCredentialStore(path, false, Base64.getEncoder().encodeToString(otherKey)).load());
        byte[] tampered = Files.readAllBytes(path);
        tampered[tampered.length - 1] ^= 1;
        Files.write(path, tampered);
        assertThrows(IOException.class, store::load);
    }

    @Test void missingKeyNeverWritesPlaintext() {
        Path path = directory.resolve("accounts.bin");
        var store = new AccountCredentialStore(path, false, null);
        assertThrows(IOException.class, () -> store.save(new JsonObject()));
        assertFalse(Files.exists(path));
    }

    @Test void windowsDpapiRoundTrip() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var store = new AccountCredentialStore(directory.resolve("accounts.bin"), true, null);
        var json = new JsonObject();
        json.addProperty("refreshToken", "dpapi-test-token");
        store.save(json);
        assertEquals(json, store.load());
    }
}
