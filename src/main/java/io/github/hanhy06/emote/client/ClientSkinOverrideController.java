package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.network.payload.EmoteSkinSyncPayload;
import io.github.hanhy06.emote.skin.EmoteSkinPart;
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
import java.util.Set;

public class ClientSkinOverrideController {
	private final Map<String, EmoteSkinSyncPayload.Entry> entryMap = new HashMap<>();
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
		this.entryMap.clear();
		for (EmoteSkinSyncPayload.Entry entry : entries) {
			this.entryMap.put(entry.namespace(), entry);
		}
	}

	public void clear() {
		this.entryMap.clear();
		this.profileMap.clear();
		this.serverHost = null;
		this.serverPort = 0;
	}

	public ItemStack createOverrideItemStack(Display.ItemDisplay itemDisplay) {
		if (this.serverHost == null || this.serverPort <= 0) {
			return null;
		}

		Display.ItemDisplay.ItemRenderState itemRenderState = itemDisplay.itemRenderState();
		if (itemRenderState == null) {
			return null;
		}

		ItemStack itemStack = itemRenderState.itemStack();
		if (!itemStack.is(Items.PLAYER_HEAD)) {
			return null;
		}

		TaggedSkinPart taggedSkinPart = findTaggedSkinPart(itemDisplay.entityTags());
		if (taggedSkinPart == null) {
			return null;
		}

		ResolvableProfile currentProfile = itemStack.get(DataComponents.PROFILE);
		if (currentProfile == null) {
			return null;
		}

		PlayerSkinHost playerSkinHost = new PlayerSkinHost(this.serverHost, this.serverPort);
		String textureToken = PlayerSkinTextureHelper.buildTextureToken(
				taggedSkinPart.entry().textureHash(),
				taggedSkinPart.entry().slimModel(),
				taggedSkinPart.skinPart().skinPart(),
				taggedSkinPart.skinPart().skinSegment()
		);
		String textureUrl = PlayerSkinTextureHelper.buildTextureUrl(playerSkinHost.createBaseUrl(), textureToken);
		String profileName = currentProfile.name().orElse(taggedSkinPart.entry().namespace());
		ResolvableProfile overrideProfile = this.profileMap.computeIfAbsent(
				textureUrl,
				ignored -> PlayerSkinTextureHelper.createProfile(profileName, textureUrl)
		);

		ItemStack overrideItemStack = itemStack.copy();
		overrideItemStack.set(DataComponents.PROFILE, overrideProfile);
		return overrideItemStack;
	}

	private TaggedSkinPart findTaggedSkinPart(Set<String> entityTags) {
		for (String entityTag : entityTags) {
			int delimiterIndex = entityTag.lastIndexOf('_');
			if (delimiterIndex <= 0 || delimiterIndex >= entityTag.length() - 1) {
				continue;
			}

			String namespace = entityTag.substring(0, delimiterIndex);
			EmoteSkinSyncPayload.Entry entry = this.entryMap.get(namespace);
			if (entry == null) {
				continue;
			}

			Integer partIndex = parsePositiveInt(entityTag.substring(delimiterIndex + 1));
			if (partIndex == null) {
				continue;
			}

			EmoteSkinPart skinPart = findSkinPart(entry.skinParts(), partIndex);
			if (skinPart != null) {
				return new TaggedSkinPart(entry, skinPart);
			}
		}

		return null;
	}

	private EmoteSkinPart findSkinPart(List<EmoteSkinPart> skinParts, int partIndex) {
		for (EmoteSkinPart skinPart : skinParts) {
			if (skinPart.partIndex() == partIndex) {
				return skinPart;
			}
		}

		return null;
	}

	private Integer parsePositiveInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return null;
		}
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

		return null;
	}

	private record TaggedSkinPart(
			EmoteSkinSyncPayload.Entry entry,
			EmoteSkinPart skinPart
	) {
	}
}
