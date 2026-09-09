package io.github.hanhy06.emote.skin.account;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.platform.win32.Crypt32Util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** Stores only encrypted bytes, including in the temporary file used for replacement. */
public final class AccountCredentialStore {
    private final Path file;
    private final boolean windows;
    private final String externalKey;

    public AccountCredentialStore(Path file) {
        this(file, System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"), System.getenv("EMOTE_ACCOUNT_KEY"));
    }

    AccountCredentialStore(Path file, boolean windows, String externalKey) {
        this.file = file;
        this.windows = windows;
        this.externalKey = externalKey;
    }

    public JsonObject load() throws IOException {
        if (!Files.exists(this.file)) {
            return new JsonObject();
        }
        byte[] encoded = Files.readAllBytes(this.file);
        byte[] plain = null;
        try {
            if (encoded.length < 2) {
                throw new IOException("Invalid account store");
            }
            byte[] payload = Arrays.copyOfRange(encoded, 1, encoded.length);
            plain = switch (encoded[0]) {
                case 1 -> {
                    if (!this.windows) throw new IOException("This account store requires Windows DPAPI");
                    yield Crypt32Util.cryptUnprotectData(payload, 1);
                }
                case 2 -> crypt(Cipher.DECRYPT_MODE, payload);
                default -> throw new IOException("Unsupported account store format");
            };
            return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (GeneralSecurityException | RuntimeException | LinkageError exception) {
            throw new IOException("Cannot decrypt account store; check the server identity or EMOTE_ACCOUNT_KEY");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public void save(JsonObject accounts) throws IOException {
        byte[] plain = new Gson().toJson(accounts).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        boolean useDpapi = this.windows && (this.externalKey == null || this.externalKey.isBlank());
        try {
            encrypted = useDpapi ? Crypt32Util.cryptProtectData(plain, 1) : crypt(Cipher.ENCRYPT_MODE, plain);
        } catch (GeneralSecurityException | RuntimeException | LinkageError exception) {
            throw new IOException("Cannot protect account store; configure EMOTE_ACCOUNT_KEY or Windows DPAPI");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }

        Files.createDirectories(this.file.getParent());
        Path temporary = Files.createTempFile(this.file.getParent(), "accounts-", ".tmp");
        try {
            byte[] encoded = new byte[encrypted.length + 1];
            encoded[0] = (byte) (useDpapi ? 1 : 2);
            System.arraycopy(encrypted, 0, encoded, 1, encrypted.length);
            Files.write(temporary, encoded);
            Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] crypt(int mode, byte[] input) throws GeneralSecurityException, IOException {
        if (this.externalKey == null || this.externalKey.isBlank()) {
            throw new IOException("Set EMOTE_ACCOUNT_KEY to a Base64-encoded 32-byte key");
        }
        byte[] key = Base64.getDecoder().decode(this.externalKey);
        try {
            if (key.length != 32) throw new IOException("EMOTE_ACCOUNT_KEY must contain 32 bytes");
            byte[] nonce = new byte[12];
            if (mode == Cipher.ENCRYPT_MODE) {
                new SecureRandom().nextBytes(nonce);
            } else {
                if (input.length < 28) throw new IOException("Invalid encrypted account store");
                System.arraycopy(input, 0, nonce, 0, nonce.length);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("emote-accounts-v1".getBytes(StandardCharsets.UTF_8));
            if (mode == Cipher.DECRYPT_MODE) return cipher.doFinal(input, nonce.length, input.length - nonce.length);
            byte[] payload = cipher.doFinal(input);
            byte[] result = Arrays.copyOf(nonce, nonce.length + payload.length);
            System.arraycopy(payload, 0, result, nonce.length, payload.length);
            return result;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
