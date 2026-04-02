package io.github.hanhy06.emote.client;

import io.github.hanhy06.emote.Emote;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClientSkinOverrideController {
	private final Map<Integer, TaggedSkinPart> skinPartMap = new HashMap<>();
	private final Map<String, ResolvableProfile> profileMap = new HashMap<>();
	private final Set<String> emittedLogKeySet = new HashSet<>();

	private String serverHost;
	private int serverPort;

	public void rememberServerHost(Minecraft client) {
		this.serverHost = readServerHost(client);
		Emote.LOGGER.info("[skin-debug/client] remembered server host={}", this.serverHost);
	}

	public void updateServerPort(int serverPort) {
		this.serverPort = serverPort;
		Emote.LOGGER.info("[skin-debug/client] updated server port={}", serverPort);
	}

	public void updateEntries(List<EmoteSkinSyncPayload.Entry> entries) {
		this.skinPartMap.clear();
		for (EmoteSkinSyncPayload.Entry entry : entries) {
			for (BoundEmoteSkinPart skinPart : entry.skinParts()) {
				this.skinPartMap.put(skinPart.entityId(), new TaggedSkinPart(entry, skinPart));
			}
		}
		Emote.LOGGER.info("[skin-debug/client] updated skin entries {}", describeEntries(entries));
	}

	public void clear() {
		this.skinPartMap.clear();
		this.profileMap.clear();
		this.emittedLogKeySet.clear();
		this.serverHost = null;
		this.serverPort = 0;
	}

	public ItemStack createOverrideItemStack(Display.ItemDisplay itemDisplay) {
		if (this.serverHost == null || this.serverPort <= 0) {
			logOnce(
					"missing-endpoint",
					"[skin-debug/client] skip override reason=missing_endpoint host={} port={}",
					this.serverHost,
					this.serverPort
			);
			return null;
		}

		Display.ItemDisplay.ItemRenderState itemRenderState = itemDisplay.itemRenderState();
		if (itemRenderState == null) {
			logOnce(
					"missing-render-state:" + itemDisplay.getId(),
					"[skin-debug/client] skip override entity={} reason=missing_render_state tags={}",
					itemDisplay.getId(),
					itemDisplay.entityTags()
			);
			return null;
		}

		ItemStack itemStack = itemRenderState.itemStack();
		if (!itemStack.is(Items.PLAYER_HEAD)) {
			return null;
		}

		TaggedSkinPart taggedSkinPart = this.skinPartMap.get(itemDisplay.getId());
		if (taggedSkinPart == null) {
			logOnce(
					"missing-bound-skin-part:" + itemDisplay.getId(),
					"[skin-debug/client] skip override entity={} reason=no_bound_skin_part tags={}",
					itemDisplay.getId(),
					itemDisplay.entityTags()
			);
			return null;
		}

		PlayerSkinHost playerSkinHost = new PlayerSkinHost(this.serverHost, this.serverPort);
		String textureToken = PlayerSkinTextureHelper.buildTextureToken(
				taggedSkinPart.entry().textureHash(),
				taggedSkinPart.entry().slimModel(),
				taggedSkinPart.boundSkinPart().skinPart().skinPart(),
				taggedSkinPart.boundSkinPart().skinPart().skinSegment()
		);
		String textureUrl = PlayerSkinTextureHelper.buildTextureUrl(playerSkinHost.createBaseUrl(), textureToken);
		ResolvableProfile currentProfile = itemStack.get(DataComponents.PROFILE);
		String profileName = currentProfile != null
				? currentProfile.name().orElse(taggedSkinPart.entry().namespace())
				: taggedSkinPart.entry().namespace();
		ResolvableProfile overrideProfile = this.profileMap.computeIfAbsent(
				textureUrl,
				ignored -> PlayerSkinTextureHelper.createProfile(profileName, textureUrl)
		);
		logOnce(
				"override-success:" + itemDisplay.getId() + ":" + textureUrl,
				"[skin-debug/client] apply override entity={} namespace={} partIndex={} skinPart={} segment={} hadProfile={} textureUrl={}",
				itemDisplay.getId(),
				taggedSkinPart.entry().namespace(),
				taggedSkinPart.boundSkinPart().skinPart().partIndex(),
				taggedSkinPart.boundSkinPart().skinPart().skinPart().id(),
				taggedSkinPart.boundSkinPart().skinPart().skinSegment().id(),
				currentProfile != null,
				textureUrl
		);

		ItemStack overrideItemStack = itemStack.copy();
		overrideItemStack.set(DataComponents.PROFILE, overrideProfile);
		return overrideItemStack;
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

	private record TaggedSkinPart(
			EmoteSkinSyncPayload.Entry entry,
			BoundEmoteSkinPart boundSkinPart
	) {
	}

	private void logOnce(String key, String message, Object... args) {
		if (this.emittedLogKeySet.add(key)) {
			Emote.LOGGER.info(message, args);
		}
	}

	private String describeEntries(List<EmoteSkinSyncPayload.Entry> entries) {
		if (entries.isEmpty()) {
			return "details=empty";
		}

		StringBuilder builder = new StringBuilder("details=");
		for (int index = 0; index < entries.size(); index++) {
			EmoteSkinSyncPayload.Entry entry = entries.get(index);
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(entry.namespace())
					.append('(')
					.append(entry.skinParts().size())
					.append(',')
					.append(entry.slimModel() ? "slim" : "wide")
					.append(')');
		}
		return builder.toString();
	}
}
