package io.github.hanhy06.emote.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

}
