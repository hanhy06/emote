package io.github.hanhy06.emote.skin;

import java.util.Objects;

record MineSkinTextureResult(String textureUrl) {
    MineSkinTextureResult {
        Objects.requireNonNull(textureUrl, "textureUrl");
        if (textureUrl.isBlank()) {
            throw new IllegalArgumentException("textureUrl must not be blank");
        }
    }
}
