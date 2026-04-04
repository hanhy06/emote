package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.EmoteSkinSyncPayload;
import io.github.hanhy06.emote.playback.data.BoundEmoteSkinPart;
import io.github.hanhy06.emote.skin.PlayerSkinHost;
import io.github.hanhy06.emote.skin.PlayerSkinTextureHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClientSkinOverrideController {
	private final Map<Integer, TaggedSkinPart> skinPartMap = new HashMap<>();
	private final Map<String, ResolvableProfile> profileMap = new HashMap<>();

	private String serverHost;
	private int serverPort;

	public void rememberServerHost(Minecraft client) {
		this.serverHost = readServerHost(client);
	}

	public void updateServerPort(int serverPort) {
		this.serverPort = serverPort;
	}

	public void updateEntries(List<EmoteSkinSyncPayload.Entry> entries) {
		this.skinPartMap.clear();
		for (EmoteSkinSyncPayload.Entry entry : entries) {
			for (int index = 0; index < entry.skinParts().size(); index++) {
				BoundEmoteSkinPart skinPart = entry.skinParts().get(index);
				this.skinPartMap.put(skinPart.entityId(), new TaggedSkinPart(entry, skinPart, entry.textureUrls().get(index)));
			}
		}
	}

	public void clear() {
		this.skinPartMap.clear();
		this.profileMap.clear();
		this.serverHost = null;
		this.serverPort = 0;
	}

	public ItemStack createOverrideItemStack(Display.ItemDisplay itemDisplay) {
		Display.ItemDisplay.ItemRenderState itemRenderState = itemDisplay.itemRenderState();
		if (itemRenderState == null) {
			return null;
		}

		ItemStack itemStack = itemRenderState.itemStack();
		if (!itemStack.is(Items.PLAYER_HEAD)) {
			return null;
		}

		TaggedSkinPart taggedSkinPart = this.skinPartMap.get(itemDisplay.getId());
		if (taggedSkinPart == null) {
			return null;
		}

		String textureUrl = resolveTextureUrl(taggedSkinPart);
		if (textureUrl == null) {
			return null;
		}

		ResolvableProfile currentProfile = itemStack.get(DataComponents.PROFILE);
		String profileName = currentProfile != null
				? currentProfile.name().orElse(taggedSkinPart.entry().namespace())
				: taggedSkinPart.entry().namespace();
		ResolvableProfile overrideProfile = this.profileMap.computeIfAbsent(
				textureUrl,
				ignored -> PlayerSkinTextureHelper.createProfile(profileName, textureUrl)
		);

		ItemStack overrideItemStack = itemStack.copy();
		overrideItemStack.set(DataComponents.PROFILE, overrideProfile);
		return overrideItemStack;
	}

	private String resolveTextureUrl(TaggedSkinPart taggedSkinPart) {
		String savedTextureUrl = normalizeTextureUrl(taggedSkinPart.textureUrl());
		if (savedTextureUrl != null) {
			return savedTextureUrl;
		}

		if (this.serverHost == null || this.serverPort <= 0) {
			return null;
		}

		PlayerSkinHost playerSkinHost = new PlayerSkinHost(this.serverHost, this.serverPort);
		String textureToken = PlayerSkinTextureHelper.buildTextureToken(
				taggedSkinPart.entry().textureHash(),
				taggedSkinPart.entry().slimModel(),
				taggedSkinPart.boundSkinPart().skinPart().skinPart(),
				taggedSkinPart.boundSkinPart().skinPart().skinSegment()
		);
		return PlayerSkinTextureHelper.buildTextureUrl(playerSkinHost.createBaseUrl(), textureToken);
	}

	private String readServerHost(Minecraft client) {
		ServerData serverData = client.getCurrentServer();
		if (serverData != null && !serverData.ip.isBlank()) {
			return ServerAddress.parseString(serverData.ip).getHost();
		}

		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			return null;
		}

		Connection networkConnection = connection.getConnection();
		SocketAddress remoteAddress = networkConnection.getRemoteAddress();
		if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
			return inetSocketAddress.getHostString();
		}

		return "localhost";
	}

	private String normalizeTextureUrl(String textureUrl) {
		if (textureUrl == null) {
			return null;
		}

		String normalizedTextureUrl = textureUrl.trim();
		return normalizedTextureUrl.isEmpty() ? null : normalizedTextureUrl;
	}

	private record TaggedSkinPart(
			EmoteSkinSyncPayload.Entry entry,
			BoundEmoteSkinPart boundSkinPart,
			String textureUrl
	) {
	}
}
