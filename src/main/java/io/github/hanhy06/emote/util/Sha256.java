package io.github.hanhy06.emote.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Sha256 {
    private Sha256() {
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String hashHex(byte[] value) {
        return HexFormat.of().formatHex(newDigest().digest(value));
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
