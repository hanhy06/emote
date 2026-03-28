package io.github.hanhy06.emote.skin;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlayerSkinManager implements ConfigListener {
	private static final String HTTP_PATH_PREFIX = "/emote/skin/";
	private static final byte[] HEADER_END = new byte[]{'\r', '\n', '\r', '\n'};
	private static final int MAX_HTTP_REQUEST_SIZE = 8192;
	private static final double SKIN_SEARCH_DISTANCE = 8.0D;
	private static final AttributeKey<byte[]> HTTP_REQUEST_BUFFER = AttributeKey.valueOf("emote_http_request_buffer");

	private final PlayerSkinHostStore playerSkinHostStore = new PlayerSkinHostStore();
	private final PlayerSkinTextureStore playerSkinTextureStore = new PlayerSkinTextureStore();
	private final PlayerSkinBaker playerSkinBaker = new PlayerSkinBaker();
	private final ConcurrentMap<String, PlayerSkinTextureSet> playerSkinTextureSetMap = new ConcurrentHashMap<>();
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

	public void applyPlayerSkin(ServerPlayer player, EmoteDefinition definition) {
		try {
			applyPlayerSkinInternal(player, definition);
		} catch (RuntimeException exception) {
			Emote.LOGGER.warn("skin apply failed for " + player.getGameProfile().name(), exception);
		}
	}

	private void applyPlayerSkinInternal(ServerPlayer player, EmoteDefinition definition) {
		List<EmoteSkinPart> skinParts = definition.skinParts();
		if (skinParts.isEmpty()) {
			return;
		}

		Optional<PlayerSkinHost> playerSkinHost = findPlayerSkinHost(player);
		if (playerSkinHost.isEmpty()) {
			return;
		}

		Optional<PlayerSkinTextureSet> playerSkinTextureSet = loadPlayerSkinTextureSet(player);
		if (playerSkinTextureSet.isEmpty()) {
			return;
		}

		Map<PlayerSkinPart, ResolvableProfile> profileMap = createProfileMap(player, playerSkinHost.get(), playerSkinTextureSet.get());
		if (profileMap.isEmpty()) {
			return;
		}

		AABB searchBox = player.getBoundingBox().inflate(SKIN_SEARCH_DISTANCE);
		for (EmoteSkinPart skinPart : skinParts) {
			ResolvableProfile profile = profileMap.get(skinPart.skinPart());
			if (profile == null) {
				continue;
			}

			applyProfile(player, searchBox, definition.namespace(), skinPart.partIndex(), profile);
		}
	}

	public boolean handleHttpRequest(ChannelHandlerContext context, ByteBuf input) {
		byte[] bufferedRequest = appendHttpRequestBuffer(context, input);
		if (bufferedRequest == null) {
			return false;
		}

		if (bufferedRequest.length > MAX_HTTP_REQUEST_SIZE) {
			clearHttpRequestBuffer(context);
			writeResponse(context, 413, "Request Too Large", "text/plain; charset=utf-8", "Request too large.".getBytes(StandardCharsets.UTF_8), false);
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
			writeResponse(context, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request.".getBytes(StandardCharsets.UTF_8), false);
			return true;
		}

		if (!request.path().startsWith(HTTP_PATH_PREFIX) || !request.path().endsWith(".png")) {
			writeResponse(context, 404, "Not Found", "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8), request.headOnly());
			return true;
		}

		String token = request.path().substring(HTTP_PATH_PREFIX.length(), request.path().length() - 4);
		Optional<byte[]> pngBytes = this.playerSkinTextureStore.find(token);
		if (pngBytes.isEmpty()) {
			writeResponse(context, 404, "Not Found", "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8), request.headOnly());
			return true;
		}

		writeResponse(context, 200, "OK", "image/png", pngBytes.get(), request.headOnly());
		return true;
	}

	private void handleHttpExchange(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			boolean headOnly;
			if ("GET".equalsIgnoreCase(method)) {
				headOnly = false;
			} else if ("HEAD".equalsIgnoreCase(method)) {
				headOnly = true;
			} else {
				writeExchangeResponse(exchange, 405, "text/plain; charset=utf-8", "Method not allowed.".getBytes(StandardCharsets.UTF_8), false);
				return;
			}

			String path = exchange.getRequestURI().getPath();
			if (path == null || !path.startsWith(HTTP_PATH_PREFIX) || !path.endsWith(".png")) {
				writeExchangeResponse(exchange, 404, "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8), headOnly);
				return;
			}

			String token = path.substring(HTTP_PATH_PREFIX.length(), path.length() - 4);
			Optional<byte[]> pngBytes = this.playerSkinTextureStore.find(token);
			if (pngBytes.isEmpty()) {
				writeExchangeResponse(exchange, 404, "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8), headOnly);
				return;
			}

			writeExchangeResponse(exchange, 200, "image/png", pngBytes.get(), headOnly);
		} finally {
			exchange.close();
		}
	}

	public void clear() {
		stopHttpServer();
		this.playerSkinHostStore.clear();
		this.playerSkinTextureStore.clear();
		this.playerSkinTextureSetMap.clear();
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

	private Optional<PlayerSkinHost> findPlayerSkinHost(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return Optional.empty();
		}

		Connection connection = ((ServerCommonPacketListenerImplAccessor) player.connection).emote$getConnection();
		Optional<PlayerSkinHost> storedHost = this.playerSkinHostStore.find(connection);
		if (storedHost.isPresent()) {
			int resolvedPort = resolvePlayerSkinPort(server, storedHost.get().port());
			if (resolvedPort <= 0) {
				return Optional.empty();
			}

			return Optional.of(new PlayerSkinHost(
				storedHost.get().host(),
				resolvedPort
			));
		}

		String localIp = server.getLocalIp();
		if (localIp == null || localIp.isBlank()) {
			localIp = "localhost";
		}

		int resolvedPort = resolvePlayerSkinPort(server, server.getPort());
		if (resolvedPort <= 0) {
			return Optional.empty();
		}

		return Optional.of(new PlayerSkinHost(localIp, resolvedPort));
	}

	private Optional<PlayerSkinTextureSet> loadPlayerSkinTextureSet(ServerPlayer player) {
		Optional<PlayerSkinSource> playerSkinSource = readPlayerSkinSource(player);
		if (playerSkinSource.isEmpty()) {
			return Optional.empty();
		}

		PlayerSkinSource skinSource = playerSkinSource.get();
		String cacheKey = skinSource.textureHash() + ":" + (skinSource.slimModel() ? "slim" : "wide");
		PlayerSkinTextureSet currentTextureSet = this.playerSkinTextureSetMap.get(cacheKey);
		if (currentTextureSet != null) {
			return Optional.of(currentTextureSet);
		}

		try {
			BufferedImage sourceImage = downloadSkinImage(skinSource.textureUrl());
			Map<PlayerSkinPart, String> tokenMap = new EnumMap<>(PlayerSkinPart.class);
			for (PlayerSkinPart skinPart : PlayerSkinPart.values()) {
				String token = buildTextureToken(skinSource.textureHash(), skinSource.slimModel(), skinPart);
				this.playerSkinTextureStore.put(token, this.playerSkinBaker.bake(sourceImage, skinPart, skinSource.slimModel()));
				tokenMap.put(skinPart, token);
			}

			PlayerSkinTextureSet loadedTextureSet = new PlayerSkinTextureSet(Map.copyOf(tokenMap));
			PlayerSkinTextureSet existingTextureSet = this.playerSkinTextureSetMap.putIfAbsent(cacheKey, loadedTextureSet);
			return Optional.of(existingTextureSet != null ? existingTextureSet : loadedTextureSet);
		} catch (IOException | IllegalArgumentException exception) {
			Emote.LOGGER.warn("skin load failed for " + player.getGameProfile().name(), exception);
			return Optional.empty();
		}
	}

	private Optional<PlayerSkinSource> readPlayerSkinSource(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return Optional.empty();
		}

		Property packedTextures = server.services().sessionService().getPackedTextures(player.getGameProfile());
		if (packedTextures == null) {
			return Optional.empty();
		}

		MinecraftProfileTextures unpackedTextures = server.services().sessionService().unpackTextures(packedTextures);
		MinecraftProfileTexture skinTexture = unpackedTextures.skin();
		if (skinTexture == null) {
			return Optional.empty();
		}

		boolean slimModel = "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
		return Optional.of(new PlayerSkinSource(skinTexture.getHash(), skinTexture.getUrl(), slimModel));
	}

	private BufferedImage downloadSkinImage(String textureUrl) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create(textureUrl).toURL().openConnection();
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
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

	private Map<PlayerSkinPart, ResolvableProfile> createProfileMap(
		ServerPlayer player,
		PlayerSkinHost playerSkinHost,
		PlayerSkinTextureSet playerSkinTextureSet
	) {
		Map<PlayerSkinPart, ResolvableProfile> profileMap = new EnumMap<>(PlayerSkinPart.class);
		for (Map.Entry<PlayerSkinPart, String> entry : playerSkinTextureSet.textureTokenMap().entrySet()) {
			String textureUrl = playerSkinHost.createBaseUrl() + HTTP_PATH_PREFIX + entry.getValue() + ".png";
			profileMap.put(entry.getKey(), createProfile(player, textureUrl));
		}

		return profileMap;
	}

	private ResolvableProfile createProfile(ServerPlayer player, String textureUrl) {
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(textureUrl.getBytes(StandardCharsets.UTF_8)), player.getGameProfile().name());
		profile.properties().put("textures", new Property("textures", encodeTextureValue(player, textureUrl)));
		return ResolvableProfile.createResolved(profile);
	}

	private String encodeTextureValue(ServerPlayer player, String textureUrl) {
		JsonObject rootObject = new JsonObject();
		rootObject.addProperty("timestamp", System.currentTimeMillis());
		rootObject.addProperty("profileId", player.getUUID().toString().replace("-", ""));
		rootObject.addProperty("profileName", player.getGameProfile().name());

		JsonObject texturesObject = new JsonObject();
		JsonObject skinObject = new JsonObject();
		skinObject.addProperty("url", textureUrl);
		texturesObject.add("SKIN", skinObject);
		rootObject.add("textures", texturesObject);
		return Base64.getEncoder().encodeToString(rootObject.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void applyProfile(ServerPlayer player, AABB searchBox, String namespace, int partIndex, ResolvableProfile profile) {
		if (!(player.level() instanceof ServerLevel serverLevel)) {
			return;
		}

		String partTag = namespace + "_" + partIndex;
		List<Display.ItemDisplay> itemDisplays = serverLevel.getEntitiesOfClass(
			Display.ItemDisplay.class,
			searchBox,
			itemDisplay -> itemDisplay.entityTags().contains(partTag)
		);
		if (itemDisplays.isEmpty()) {
			return;
		}

		SlotAccess slot = itemDisplays.get(0).getSlot(0);
		if (slot == null) {
			return;
		}

		ItemStack itemStack = slot.get().copy();
		if (!itemStack.is(Items.PLAYER_HEAD)) {
			return;
		}

		itemStack.set(DataComponents.PROFILE, profile);
		slot.set(itemStack);
	}

	private String buildTextureToken(String textureHash, boolean slimModel, PlayerSkinPart skinPart) {
		return textureHash.toLowerCase(Locale.ROOT) + "-" + (slimModel ? "slim" : "wide") + "-" + skinPart.id();
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
		String[] requestParts = requestLine.split(" ");
		if (requestParts.length != 3) {
			return null;
		}

		boolean headOnly;
		if ("GET".equals(requestParts[0])) {
			headOnly = false;
		} else if ("HEAD".equals(requestParts[0])) {
			headOnly = true;
		} else {
			return null;
		}

		if (!requestParts[2].startsWith("HTTP/1.")) {
			return null;
		}

		return new ParsedHttpRequest(requestParts[1], headOnly);
	}

	private void clearHttpRequestBuffer(ChannelHandlerContext context) {
		context.channel().attr(HTTP_REQUEST_BUFFER).set(null);
	}

	private void writeExchangeResponse(
		HttpExchange exchange,
		int statusCode,
		String contentType,
		byte[] bodyBytes,
		boolean headOnly
	) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Content-Length", Integer.toString(bodyBytes.length));
		exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
		if (headOnly) {
			exchange.sendResponseHeaders(statusCode, -1);
			return;
		}

		exchange.sendResponseHeaders(statusCode, bodyBytes.length);
		exchange.getResponseBody().write(bodyBytes);
	}

	private void writeResponse(
		ChannelHandlerContext context,
		int statusCode,
		String statusText,
		String contentType,
		byte[] bodyBytes,
		boolean headOnly
	) {
		String headerText = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
			+ "Content-Type: " + contentType + "\r\n"
			+ "Content-Length: " + bodyBytes.length + "\r\n"
			+ "Cache-Control: public, max-age=31536000, immutable\r\n"
			+ "Connection: close\r\n\r\n";

		ByteBuf headerBuffer = Unpooled.copiedBuffer(headerText, StandardCharsets.US_ASCII);
		ByteBuf responseBuffer = headOnly
			? headerBuffer
			: Unpooled.wrappedBuffer(headerBuffer, Unpooled.wrappedBuffer(bodyBytes));
		context.pipeline().firstContext().writeAndFlush(responseBuffer).addListener(ChannelFutureListener.CLOSE);
	}

	private record ParsedHttpRequest(String path, boolean headOnly) {
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

	private record PlayerSkinTextureSet(Map<PlayerSkinPart, String> textureTokenMap) {
		private PlayerSkinTextureSet {
			textureTokenMap = Map.copyOf(textureTokenMap);
		}
	}
}
