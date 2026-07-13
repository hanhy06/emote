package io.github.hanhy06.emote.skin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MineSkinContentKey {
    private MineSkinContentKey() {
    }

    static String create(byte[] pngBytes, boolean slimModel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (slimModel ? 1 : 0));
            digest.update(pngBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
