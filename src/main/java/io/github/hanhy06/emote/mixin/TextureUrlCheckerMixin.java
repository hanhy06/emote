package io.github.hanhy06.emote.mixin;

import com.mojang.authlib.yggdrasil.TextureUrlChecker;
import io.github.hanhy06.emote.Emote;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Mixin(value = TextureUrlChecker.class, remap = false)
public class TextureUrlCheckerMixin {
	@Unique
	private static final Set<String> ALLOWED_TEXTURE_LOG_SET = Collections.synchronizedSet(new HashSet<>());

	@Inject(method = "isAllowedTextureDomain", at = @At("HEAD"), cancellable = true, remap = false)
	private static void emote$allowHostedSkinTextures(String textureUrl, CallbackInfoReturnable<Boolean> callbackInfo) {
		if (textureUrl == null || textureUrl.isBlank()) {
			return;
		}

		try {
			URI normalizedUri = new URI(textureUrl).normalize();
			String scheme = normalizedUri.getScheme();
			String host = normalizedUri.getHost();
			String path = normalizedUri.getPath();
			if (host == null || path == null) {
				return;
			}

			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				return;
			}

			if (path.startsWith("/emote/skin/") && path.endsWith(".png")) {
				if (ALLOWED_TEXTURE_LOG_SET.add(normalizedUri.toString())) {
					Emote.LOGGER.info("[skin-debug/client] allow hosted texture url={}", normalizedUri);
				}
				callbackInfo.setReturnValue(true);
			}
		} catch (URISyntaxException ignored) {
		}
	}
}
