package io.github.hanhy06.emote.skin;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import io.github.hanhy06.emote.mixin.ServerCommonPacketListenerImplAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlayerSkinManager implements ConfigListener {
	private static final String HTTP_PATH_PREFIX = "/emote/skin/";
	private static final String PNG_PATH_SUFFIX = ".png";
	private static final String TEXT_PLAIN_CONTENT_TYPE = "text/plain; charset=utf-8";
	private static final String PNG_CONTENT_TYPE = "image/png";
	private static final String TEXTURE_TOKEN_VERSION = "v25";
	private static final byte[] BAD_REQUEST_RESPONSE_BODY = "Bad request.".getBytes(StandardCharsets.UTF_8);
	private static final byte[] HEADER_END = new byte[]{'\r', '\n', '\r', '\n'};
	private static final byte[] METHOD_NOT_ALLOWED_RESPONSE_BODY = "Method not allowed.".getBytes(StandardCharsets.UTF_8);
	private static final int MAX_HTTP_REQUEST_SIZE = 8192;
	private static final byte[] NOT_FOUND_RESPONSE_BODY = "Not found.".getBytes(StandardCharsets.UTF_8);
	private static final byte[] REQUEST_TOO_LARGE_RESPONSE_BODY = "Request too large.".getBytes(StandardCharsets.UTF_8);
	private static final int SKIN_APPLY_RETRY_COUNT = 10;
	private static final int SKIN_DOWNLOAD_TIMEOUT_MILLIS = 5000;
	private static final double SKIN_SEARCH_DISTANCE = 8.0D;
	private static final AttributeKey<byte[]> HTTP_REQUEST_BUFFER = AttributeKey.valueOf("emote_http_request_buffer");

	private final PlayerSkinHostStore playerSkinHostStore = new PlayerSkinHostStore();
	private final PlayerSkinTextureStore playerSkinTextureStore = new PlayerSkinTextureStore();
	private final PlayerSkinBaker playerSkinBaker = new PlayerSkinBaker();
	private final ConcurrentMap<String, ConcurrentMap<PlayerSkinTextureKey, String>> playerSkinTextureSetMap = new ConcurrentHashMap<>();
	private final Set<UUID> clientSkinSupportPlayerSet = ConcurrentHashMap.newKeySet();
	private final ConcurrentMap<UUID, PendingPlayerSkinApplication> pendingSkinApplicationMap = new ConcurrentHashMap<>();
	private final Object httpServerLock = new Object();

	private volatile int configuredPort;
	private volatile HttpServer httpServer;
	private volatile int httpServerPort;

	@Override
	public void onConfigReload(Config newConfig) {
		this.configuredPort = newConfig.player_skin_port();
	}

	public void reloadHttpServer(MinecraftServer server) {
		int nextPort = resolveHttpServerPort(server);
		if (nextPort < 0) {
			stopHttpServer();
			return;
		}

		synchronized (this.httpServerLock) {
			if (this.httpServer != null && (nextPort == 0 || this.httpServerPort == nextPort)) {
				return;
			}

			stopHttpServerLocked();

			try {
				HttpServer nextServer = HttpServer.create(new InetSocketAddress(nextPort), 0);
				nextServer.createContext(HTTP_PATH_PREFIX, this::handleHttpExchange);
				nextServer.setExecutor(null);
				nextServer.start();
				this.httpServer = nextServer;
				this.httpServerPort = nextServer.getAddress().getPort();
				Emote.LOGGER.info("skin port={}", this.httpServerPort);
			} catch (IOException exception) {
				this.httpServer = null;
				this.httpServerPort = 0;
				Emote.LOGGER.warn("skin port failed: {}", nextPort);
			}
		}
	}

	public void stopHttpServer() {
		synchronized (this.httpServerLock) {
			stopHttpServerLocked();
		}
	}

	public void rememberConnectionHost(Connection connection, String host, int port) {
		this.playerSkinHostStore.remember(connection, host, port);
	}

	public void markClientSkinSupport(ServerPlayer player) {
		this.clientSkinSupportPlayerSet.add(player.getUUID());
	}

	public void forgetClientSkinSupport(ServerPlayer player) {
		this.clientSkinSupportPlayerSet.remove(player.getUUID());
		this.pendingSkinApplicationMap.remove(player.getUUID());
	}

	public void applyPlayerSkin(ServerPlayer player, EmoteDefinition definition) {
		PlayerSkinApplyResult result = applyPlayerSkinSafely(player, definition);
		if (result.complete() || !result.retryable()) {
			this.pendingSkinApplicationMap.remove(player.getUUID());
			return;
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}

		this.pendingSkinApplicationMap.put(
			player.getUUID(),
			new PendingPlayerSkinApplication(definition, SKIN_APPLY_RETRY_COUNT, server.getTickCount() + 1L)
		);
	}

	public void tick(MinecraftServer server) {
		if (this.pendingSkinApplicationMap.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		for (Map.Entry<UUID, PendingPlayerSkinApplication> entry : this.pendingSkinApplicationMap.entrySet()) {
			PendingPlayerSkinApplication pendingApplication = entry.getValue();
			if (pendingApplication == null || currentTick < pendingApplication.nextAttemptTick()) {
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				this.pendingSkinApplicationMap.remove(entry.getKey(), pendingApplication);
				continue;
			}

			PlayerSkinApplyResult result = applyPlayerSkinSafely(player, pendingApplication.definition());
			if (result.complete() || !result.retryable()) {
				this.pendingSkinApplicationMap.remove(entry.getKey(), pendingApplication);
				continue;
			}

			if (pendingApplication.remainingAttempts() <= 1) {
				Emote.LOGGER.warn(
					"skin apply incomplete for {}: {}/{} parts",
					player.getGameProfile().name(),
					result.appliedPartCount(),
					result.expectedPartCount()
				);
				this.pendingSkinApplicationMap.remove(entry.getKey(), pendingApplication);
				continue;
			}

			this.pendingSkinApplicationMap.replace(
				entry.getKey(),
				pendingApplication,
				pendingApplication.nextAttempt(currentTick + 1L)
			);
		}
	}

	private PlayerSkinApplyResult applyPlayerSkinSafely(ServerPlayer player, EmoteDefinition definition) {
		try {
			return applyPlayerSkinInternal(player, definition);
		} catch (RuntimeException exception) {
			Emote.LOGGER.warn("skin apply failed for " + player.getGameProfile().name(), exception);
			return PlayerSkinApplyResult.failure(definition.skinParts().size());
		}
	}

	private PlayerSkinApplyResult applyPlayerSkinInternal(ServerPlayer player, EmoteDefinition definition) {
		List<EmoteSkinPart> skinParts = definition.skinParts();
		if (skinParts.isEmpty()) {
			return PlayerSkinApplyResult.empty();
		}

		if (!this.clientSkinSupportPlayerSet.contains(player.getUUID())) {
			return PlayerSkinApplyResult.retry(skinParts.size());
		}

		PlayerSkinHost playerSkinHost = findPlayerSkinHost(player);
		if (playerSkinHost == null) {
			return PlayerSkinApplyResult.retry(skinParts.size());
		}

		Map<PlayerSkinTextureKey, String> playerSkinTextureSet = loadPlayerSkinTextureSet(player, skinParts);
		if (playerSkinTextureSet == null) {
			return PlayerSkinApplyResult.retry(skinParts.size());
		}

		Map<String, ResolvableProfile> taggedProfileMap = createTaggedProfileMap(
			player,
			playerSkinHost,
			definition.namespace(),
			skinParts,
			playerSkinTextureSet
		);
		if (taggedProfileMap.isEmpty()) {
			return PlayerSkinApplyResult.retry(skinParts.size());
		}

		AABB searchBox = player.getBoundingBox().inflate(SKIN_SEARCH_DISTANCE);
		int appliedPartCount = applyProfiles(player, searchBox, taggedProfileMap);

		return PlayerSkinApplyResult.ofApplied(skinParts.size(), appliedPartCount);
	}

	public boolean handleHttpRequest(ChannelHandlerContext context, ByteBuf input) {
		byte[] bufferedRequest = appendHttpRequestBuffer(context, input);
		if (bufferedRequest == null) {
			return false;
		}

		if (bufferedRequest.length > MAX_HTTP_REQUEST_SIZE) {
			clearHttpRequestBuffer(context);
			writeResponse(context, PlayerSkinHttpResponse.text(413, "Request Too Large", REQUEST_TOO_LARGE_RESPONSE_BODY, false));
			return true;
		}

		int headerEndIndex = indexOf(bufferedRequest, HEADER_END);
		if (headerEndIndex < 0) {
			return true;
		}

		clearHttpRequestBuffer(context);
		String headerText = new String(bufferedRequest, 0, headerEndIndex, StandardCharsets.US_ASCII);
		int requestLineEndIndex = headerText.indexOf("\r\n");
		String requestLine = requestLineEndIndex >= 0 ? headerText.substring(0, requestLineEndIndex) : headerText;
		ParsedHttpRequest request = parseRequestLine(requestLine);
		if (request == null) {
			writeResponse(context, PlayerSkinHttpResponse.text(400, "Bad Request", BAD_REQUEST_RESPONSE_BODY, false));
			return true;
		}

		writeResponse(context, createTextureResponse(request.path(), request.headOnly()));
		return true;
	}

	private void handleHttpExchange(HttpExchange exchange) throws IOException {
		try {
			Boolean headOnly = parseHeadOnly(exchange.getRequestMethod());
			if (headOnly == null) {
				writeExchangeResponse(exchange, PlayerSkinHttpResponse.text(405, "Method Not Allowed", METHOD_NOT_ALLOWED_RESPONSE_BODY, false));
				return;
			}

			writeExchangeResponse(exchange, createTextureResponse(exchange.getRequestURI().getPath(), headOnly));
		} finally {
			exchange.close();
		}
	}

	public void clear() {
		stopHttpServer();
		this.playerSkinHostStore.clear();
		this.playerSkinTextureStore.clear();
		this.playerSkinTextureSetMap.clear();
		this.clientSkinSupportPlayerSet.clear();
		this.pendingSkinApplicationMap.clear();
	}

	private int resolvePlayerSkinPort(MinecraftServer server, int fallbackPort) {
		if (this.httpServerPort > 0) {
			return this.httpServerPort;
		}

		return fallbackPort > 0 ? fallbackPort : server.getPort();
	}

	private int resolveHttpServerPort(MinecraftServer server) {
		if (this.configuredPort > 0) {
			return this.configuredPort == server.getPort() ? -1 : this.configuredPort;
		}

		return server.getPort() > 0 ? -1 : 0;
	}

	private void stopHttpServerLocked() {
		if (this.httpServer == null) {
			this.httpServerPort = 0;
			return;
		}

		this.httpServer.stop(0);
		this.httpServer = null;
		this.httpServerPort = 0;
	}

	private PlayerSkinHost findPlayerSkinHost(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return null;
		}

		Connection connection = ((ServerCommonPacketListenerImplAccessor) player.connection).emote$getConnection();
		PlayerSkinHost storedHost = this.playerSkinHostStore.find(connection);
		if (storedHost != null) {
			int resolvedPort = resolvePlayerSkinPort(server, storedHost.port());
			if (resolvedPort <= 0) {
				return null;
			}

			return new PlayerSkinHost(
				storedHost.host(),
				resolvedPort
			);
		}

		String localIp = server.getLocalIp();
		if (localIp == null || localIp.isBlank()) {
			localIp = "localhost";
		}

		int resolvedPort = resolvePlayerSkinPort(server, server.getPort());
		if (resolvedPort <= 0) {
			return null;
		}

		return new PlayerSkinHost(localIp, resolvedPort);
	}

	private Map<PlayerSkinTextureKey, String> loadPlayerSkinTextureSet(ServerPlayer player, List<EmoteSkinPart> skinParts) {
		PlayerSkinSource skinSource = readPlayerSkinSource(player);
		if (skinSource == null) {
			return null;
		}

		String cacheKey = skinSource.textureHash() + ":" + (skinSource.slimModel() ? "slim" : "wide");
		ConcurrentMap<PlayerSkinTextureKey, String> cachedTextureTokens = this.playerSkinTextureSetMap.computeIfAbsent(
			cacheKey,
			ignored -> new ConcurrentHashMap<>()
		);
		Set<PlayerSkinTextureKey> requiredTextureKeys = createTextureKeys(skinParts);

		try {
			BufferedImage sourceImage = null;
			for (PlayerSkinTextureKey textureKey : requiredTextureKeys) {
				if (cachedTextureTokens.containsKey(textureKey)) {
					continue;
				}

				if (sourceImage == null) {
					sourceImage = downloadSkinImage(skinSource.textureUrl());
				}

				String token = buildTextureToken(skinSource.textureHash(), skinSource.slimModel(), textureKey.skinPart(), textureKey.skinSegment());
				this.playerSkinTextureStore.put(token, this.playerSkinBaker.bake(sourceImage, textureKey.skinPart(), textureKey.skinSegment(), skinSource.slimModel()));
				cachedTextureTokens.putIfAbsent(textureKey, token);
			}

			Map<PlayerSkinTextureKey, String> tokenMap = new HashMap<>(requiredTextureKeys.size());
			for (PlayerSkinTextureKey textureKey : requiredTextureKeys) {
				String token = cachedTextureTokens.get(textureKey);
				if (token != null) {
					tokenMap.put(textureKey, token);
				}
			}

			return Map.copyOf(tokenMap);
		} catch (IOException | IllegalArgumentException exception) {
			Emote.LOGGER.warn("skin load failed for " + player.getGameProfile().name(), exception);
			return null;
		}
	}

	private Set<PlayerSkinTextureKey> createTextureKeys(List<EmoteSkinPart> skinParts) {
		Set<PlayerSkinTextureKey> textureKeys = new LinkedHashSet<>(skinParts.size());
		for (EmoteSkinPart skinPart : skinParts) {
			textureKeys.add(new PlayerSkinTextureKey(skinPart.skinPart(), skinPart.skinSegment()));
		}

		return textureKeys;
	}

	private PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return null;
		}

		Property packedTextures = server.services().sessionService().getPackedTextures(player.getGameProfile());
		if (packedTextures == null) {
			return null;
		}

		MinecraftProfileTextures unpackedTextures = server.services().sessionService().unpackTextures(packedTextures);
		MinecraftProfileTexture skinTexture = unpackedTextures.skin();
		if (skinTexture == null) {
			return null;
		}

		boolean slimModel = "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
		return new PlayerSkinSource(skinTexture.getHash(), skinTexture.getUrl(), slimModel);
	}

	private PlayerSkinHttpResponse createTextureResponse(String path, boolean headOnly) {
		byte[] pngBytes = findTextureBytes(path);
		if (pngBytes == null) {
			return PlayerSkinHttpResponse.text(404, "Not Found", NOT_FOUND_RESPONSE_BODY, headOnly);
		}

		return new PlayerSkinHttpResponse(200, "OK", PNG_CONTENT_TYPE, pngBytes, headOnly);
	}

	private BufferedImage downloadSkinImage(String textureUrl) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create(textureUrl).toURL().openConnection();
		connection.setConnectTimeout(SKIN_DOWNLOAD_TIMEOUT_MILLIS);
		connection.setReadTimeout(SKIN_DOWNLOAD_TIMEOUT_MILLIS);
		connection.setInstanceFollowRedirects(true);

		try {
			int responseCode = connection.getResponseCode();
			if (responseCode / 100 != 2) {
				throw new IOException("unexpected skin response: " + responseCode);
			}

			byte[] imageBytes = connection.getInputStream().readAllBytes();
			BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
			if (bufferedImage == null) {
				throw new IOException("skin image decode failed");
			}

			return bufferedImage;
		} finally {
			connection.disconnect();
		}
	}

	private Map<String, ResolvableProfile> createTaggedProfileMap(
		ServerPlayer player,
		PlayerSkinHost playerSkinHost,
		String namespace,
		List<EmoteSkinPart> skinParts,
		Map<PlayerSkinTextureKey, String> textureTokenMap
	) {
		String baseUrl = playerSkinHost.createBaseUrl();
		Map<String, ResolvableProfile> taggedProfileMap = new HashMap<>(skinParts.size());
		for (EmoteSkinPart skinPart : skinParts) {
			String textureToken = textureTokenMap.get(new PlayerSkinTextureKey(skinPart.skinPart(), skinPart.skinSegment()));
			if (textureToken == null) {
				continue;
			}

			String textureUrl = baseUrl + HTTP_PATH_PREFIX + textureToken + PNG_PATH_SUFFIX;
			taggedProfileMap.put(namespace + "_" + skinPart.partIndex(), createProfile(player, textureUrl));
		}

		return taggedProfileMap;
	}

	private ResolvableProfile createProfile(ServerPlayer player, String textureUrl) {
		UUID profileId = UUID.nameUUIDFromBytes(textureUrl.getBytes(StandardCharsets.UTF_8));
		String profileName = player.getGameProfile().name();
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of(
			"textures",
			new Property("textures", encodeTextureValue(profileId, profileName, textureUrl))
		));
		GameProfile profile = new GameProfile(
			profileId,
			profileName,
			properties
		);
		return ResolvableProfile.createResolved(profile);
	}

	private String encodeTextureValue(UUID profileId, String profileName, String textureUrl) {
		JsonObject rootObject = new JsonObject();
		rootObject.addProperty("timestamp", System.currentTimeMillis());
		rootObject.addProperty("profileId", profileId.toString().replace("-", ""));
		rootObject.addProperty("profileName", profileName);

		JsonObject texturesObject = new JsonObject();
		JsonObject skinObject = new JsonObject();
		skinObject.addProperty("url", textureUrl);
		texturesObject.add("SKIN", skinObject);
		rootObject.add("textures", texturesObject);
		return Base64.getEncoder().encodeToString(rootObject.toString().getBytes(StandardCharsets.UTF_8));
	}

	private int applyProfiles(ServerPlayer player, AABB searchBox, Map<String, ResolvableProfile> taggedProfileMap) {
		if (!(player.level() instanceof ServerLevel serverLevel)) {
			return 0;
		}

		if (taggedProfileMap.isEmpty()) {
			return 0;
		}

		Set<String> requestedTags = taggedProfileMap.keySet();
		List<Display.ItemDisplay> itemDisplays = serverLevel.getEntitiesOfClass(
			Display.ItemDisplay.class,
			searchBox,
			itemDisplay -> containsRequestedTag(itemDisplay.entityTags(), requestedTags)
		);

		Set<String> appliedTags = new HashSet<>(requestedTags.size());
		for (Display.ItemDisplay itemDisplay : itemDisplays) {
			String requestedTag = findRequestedTag(itemDisplay.entityTags(), requestedTags);
			if (requestedTag == null) {
				continue;
			}

			if (applyProfile(itemDisplay, taggedProfileMap.get(requestedTag))) {
				appliedTags.add(requestedTag);
				if (appliedTags.size() == requestedTags.size()) {
					break;
				}
			}
		}

		return appliedTags.size();
	}

	private boolean containsRequestedTag(Set<String> entityTags, Set<String> requestedTags) {
		for (String entityTag : entityTags) {
			if (requestedTags.contains(entityTag)) {
				return true;
			}
		}

		return false;
	}

	private String findRequestedTag(Set<String> entityTags, Set<String> requestedTags) {
		for (String entityTag : entityTags) {
			if (requestedTags.contains(entityTag)) {
				return entityTag;
			}
		}

		return null;
	}

	private boolean applyProfile(Display.ItemDisplay itemDisplay, ResolvableProfile profile) {
		SlotAccess slot = itemDisplay.getSlot(0);
		if (slot == null) {
			return false;
		}

		ItemStack currentStack = slot.get();
		if (!currentStack.is(Items.PLAYER_HEAD)) {
			return false;
		}

		ItemStack itemStack = currentStack.copy();
		itemStack.set(DataComponents.PROFILE, profile);
		return slot.set(itemStack);
	}

	private String buildTextureToken(String textureHash, boolean slimModel, PlayerSkinPart skinPart, PlayerSkinSegment skinSegment) {
		return TEXTURE_TOKEN_VERSION + "-" + textureHash.toLowerCase(Locale.ROOT) + "-" + (slimModel ? "slim" : "wide") + "-" + skinPart.id() + "-" + skinSegment.id();
	}

	private byte[] findTextureBytes(String path) {
		if (path == null || !path.startsWith(HTTP_PATH_PREFIX) || !path.endsWith(PNG_PATH_SUFFIX)) {
			return null;
		}

		String token = path.substring(HTTP_PATH_PREFIX.length(), path.length() - PNG_PATH_SUFFIX.length());
		return this.playerSkinTextureStore.find(token);
	}

	private byte[] appendHttpRequestBuffer(ChannelHandlerContext context, ByteBuf input) {
		int readableBytes = input.readableBytes();
		byte[] currentBytes = new byte[readableBytes];
		input.getBytes(input.readerIndex(), currentBytes);

		byte[] existingBytes = context.channel().attr(HTTP_REQUEST_BUFFER).get();
		if (existingBytes == null) {
			if (!startsWithHttpMethod(currentBytes)) {
				return null;
			}

			context.channel().attr(HTTP_REQUEST_BUFFER).set(currentBytes);
			return currentBytes;
		}

		byte[] mergedBytes = new byte[existingBytes.length + currentBytes.length];
		System.arraycopy(existingBytes, 0, mergedBytes, 0, existingBytes.length);
		System.arraycopy(currentBytes, 0, mergedBytes, existingBytes.length, currentBytes.length);
		context.channel().attr(HTTP_REQUEST_BUFFER).set(mergedBytes);
		return mergedBytes;
	}

	private boolean startsWithHttpMethod(byte[] requestBytes) {
		return startsWith(requestBytes, "GET ") || startsWith(requestBytes, "HEAD ");
	}

	private boolean startsWith(byte[] requestBytes, String prefix) {
		byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
		if (requestBytes.length < prefixBytes.length) {
			return false;
		}

		for (int index = 0; index < prefixBytes.length; index++) {
			if (requestBytes[index] != prefixBytes[index]) {
				return false;
			}
		}

		return true;
	}

	private int indexOf(byte[] valueBytes, byte[] targetBytes) {
		for (int index = 0; index <= valueBytes.length - targetBytes.length; index++) {
			boolean matched = true;
			for (int targetIndex = 0; targetIndex < targetBytes.length; targetIndex++) {
				if (valueBytes[index + targetIndex] != targetBytes[targetIndex]) {
					matched = false;
					break;
				}
			}

			if (matched) {
				return index;
			}
		}

		return -1;
	}

	private ParsedHttpRequest parseRequestLine(String requestLine) {
		String[] requestParts = requestLine.split(" ", 3);
		if (requestParts.length != 3) {
			return null;
		}

		Boolean headOnly = parseHeadOnly(requestParts[0]);
		if (headOnly == null) {
			return null;
		}

		if (!requestParts[2].startsWith("HTTP/1.")) {
			return null;
		}

		return new ParsedHttpRequest(requestParts[1], headOnly);
	}

	private Boolean parseHeadOnly(String method) {
		if ("GET".equalsIgnoreCase(method)) {
			return false;
		}

		if ("HEAD".equalsIgnoreCase(method)) {
			return true;
		}

		return null;
	}

	private void clearHttpRequestBuffer(ChannelHandlerContext context) {
		context.channel().attr(HTTP_REQUEST_BUFFER).set(null);
	}

	private void writeExchangeResponse(HttpExchange exchange, PlayerSkinHttpResponse response) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", response.contentType());
		exchange.getResponseHeaders().set("Content-Length", Integer.toString(response.bodyBytes().length));
		exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
		exchange.getResponseHeaders().set("Pragma", "no-cache");
		exchange.getResponseHeaders().set("Expires", "0");
		if (response.headOnly()) {
			exchange.sendResponseHeaders(response.statusCode(), -1);
			return;
		}

		exchange.sendResponseHeaders(response.statusCode(), response.bodyBytes().length);
		exchange.getResponseBody().write(response.bodyBytes());
	}

	private void writeResponse(ChannelHandlerContext context, PlayerSkinHttpResponse response) {
		String headerText = "HTTP/1.1 " + response.statusCode() + " " + response.statusText() + "\r\n"
			+ "Content-Type: " + response.contentType() + "\r\n"
			+ "Content-Length: " + response.bodyBytes().length + "\r\n"
			+ "Cache-Control: no-store, no-cache, must-revalidate\r\n"
			+ "Pragma: no-cache\r\n"
			+ "Expires: 0\r\n"
			+ "Connection: close\r\n\r\n";

		ByteBuf headerBuffer = Unpooled.copiedBuffer(headerText, StandardCharsets.US_ASCII);
		ByteBuf responseBuffer = response.headOnly()
			? headerBuffer
			: Unpooled.wrappedBuffer(headerBuffer, Unpooled.wrappedBuffer(response.bodyBytes()));
		context.pipeline().firstContext().writeAndFlush(responseBuffer).addListener(ChannelFutureListener.CLOSE);
	}

	private record ParsedHttpRequest(String path, boolean headOnly) {
	}

	private record PlayerSkinHttpResponse(
		int statusCode,
		String statusText,
		String contentType,
		byte[] bodyBytes,
		boolean headOnly
	) {
		private PlayerSkinHttpResponse {
			Objects.requireNonNull(statusText, "statusText");
			Objects.requireNonNull(contentType, "contentType");
			Objects.requireNonNull(bodyBytes, "bodyBytes");
		}

		private static PlayerSkinHttpResponse text(int statusCode, String statusText, byte[] bodyBytes, boolean headOnly) {
			return new PlayerSkinHttpResponse(statusCode, statusText, TEXT_PLAIN_CONTENT_TYPE, bodyBytes, headOnly);
		}
	}

	private record PlayerSkinSource(
		String textureHash,
		String textureUrl,
		boolean slimModel
	) {
		private PlayerSkinSource {
			Objects.requireNonNull(textureHash, "textureHash");
			Objects.requireNonNull(textureUrl, "textureUrl");
		}
	}

	private record PlayerSkinTextureKey(PlayerSkinPart skinPart, PlayerSkinSegment skinSegment) {
	}

	private record PendingPlayerSkinApplication(EmoteDefinition definition, int remainingAttempts, long nextAttemptTick) {
		private PendingPlayerSkinApplication nextAttempt(long nextAttemptTick) {
			return new PendingPlayerSkinApplication(this.definition, this.remainingAttempts - 1, nextAttemptTick);
		}
	}

	private record PlayerSkinApplyResult(boolean complete, boolean retryable, int expectedPartCount, int appliedPartCount) {
		private static PlayerSkinApplyResult empty() {
			return new PlayerSkinApplyResult(true, false, 0, 0);
		}

		private static PlayerSkinApplyResult retry(int expectedPartCount) {
			return new PlayerSkinApplyResult(false, true, expectedPartCount, 0);
		}

		private static PlayerSkinApplyResult failure(int expectedPartCount) {
			return new PlayerSkinApplyResult(false, false, expectedPartCount, 0);
		}

		private static PlayerSkinApplyResult ofApplied(int expectedPartCount, int appliedPartCount) {
			return new PlayerSkinApplyResult(
				appliedPartCount >= expectedPartCount,
				appliedPartCount < expectedPartCount,
				expectedPartCount,
				appliedPartCount
			);
		}
	}
}
