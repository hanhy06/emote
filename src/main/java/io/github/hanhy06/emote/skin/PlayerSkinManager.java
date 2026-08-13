package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.skin.animation.AnimationSkinBinding;
import io.github.hanhy06.emote.skin.mineskin.MineSkinCache;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinPipeline;
import io.github.hanhy06.emote.skin.mineskin.MineSkinTaskQueue;
import io.github.hanhy06.emote.skin.mineskin.PlayerSkinBaker;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSegment;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.PreparedPlayerSkin;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.playback.PlaybackNodes.ItemContent;
import io.github.hanhy06.emote.playback.PlaybackNodes.NodeInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public class PlayerSkinManager implements ConfigListener {
    private final MineSkinPipeline mineSkinManager;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();

    public PlayerSkinManager() {
        this(
            new PlayerSkinBaker(),
            new MineSkinCache(),
            new MineSkinClient(),
            new MineSkinTaskQueue(),
            PlayerSkinManager::readPlayerSkinSource
        );
    }

    PlayerSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache mineSkinCache,
        MineSkinClient mineSkinClient,
        MineSkinTaskQueue generationQueue,
        Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver
    ) {
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
        this.mineSkinManager = new MineSkinPipeline(
            Objects.requireNonNull(playerSkinBaker, "playerSkinBaker"),
            Objects.requireNonNull(mineSkinCache, "mineSkinCache"),
            Objects.requireNonNull(mineSkinClient, "mineSkinClient"),
            Objects.requireNonNull(generationQueue, "generationQueue"),
            this::notifySkinReady,
            this::notifySkinFailed
        );
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.mineSkinManager.configure(
            newConfig.mineSkinApiKey(),
            newConfig.mineSkinPollIntervalSeconds(),
            newConfig.mineSkinCacheRetentionDays(),
            newConfig.mineSkinCacheMaxMiB()
        );
    }

    public PlayerSkinPreparation preparePlayerSkin(ServerPlayer player, List<AnimationSkinBinding> skinParts) {
        if (skinParts.isEmpty()) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.READY, 100);
        }
        Set<PlayerSkinRegion> requiredTextureKeys = new LinkedHashSet<>(skinParts.size());
        for (AnimationSkinBinding skinPart : skinParts) {
            requiredTextureKeys.add(skinPart.region());
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return new PlayerSkinPreparation(null, PlayerSkinPreparation.State.UNAVAILABLE, 0);
        }
        return this.mineSkinManager.prepare(skinSource, requiredTextureKeys);
    }

    public void applySkinParts(
        Map<String, NodeInstance> nodes,
        List<AnimationSkinBinding> skinParts,
        PreparedPlayerSkin preparedPlayerSkin
    ) {
        if (preparedPlayerSkin == null || skinParts.isEmpty()) {
            return;
        }
        for (AnimationSkinBinding skinPart : skinParts) {
            NodeInstance node = nodes.get(skinPart.nodeId());
            if (node != null) {
                applyMineSkinProfile(node, skinPart.region(), preparedPlayerSkin);
            }
        }
    }

    public void addReadyListener(Consumer<UUID> readyListener) {
        this.readyListeners.add(Objects.requireNonNull(readyListener, "readyListener"));
    }

    public void cancelPendingBakes() {
        this.mineSkinManager.cancelPendingBakes();
    }

    private void applyMineSkinProfile(
        NodeInstance node,
        PlayerSkinRegion region,
        PreparedPlayerSkin preparedSkin
    ) {
        String textureUrl = preparedSkin.findTextureUrl(region);
        if (textureUrl == null) {
            return;
        }
        if (!(node.entity() instanceof Display.ItemDisplay itemDisplay)
            || !(node.displayContent() instanceof ItemContent(ItemStack itemStack))
            || !itemStack.is(Items.PLAYER_HEAD)) {
            return;
        }
        ItemStack profileStack = itemStack.copy();
        profileStack.set(DataComponents.PROFILE, PlayerHeadProfileFactory.createProfile(textureUrl));
        node.setItemStack(profileStack);

        SlotAccess itemSlot = itemDisplay.getSlot(0);
        if (itemSlot.get().isEmpty()) {
            return;
        }
        itemSlot.set(profileStack);
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Player skin is ready. Run the emote again."));
            }
            for (Consumer<UUID> readyListener : this.readyListeners) {
                readyListener.accept(playerUuid);
            }
        });
    }

    private void notifySkinFailed(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Player skin preparation failed. Try again later."));
            }
        });
    }

    private static PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        MinecraftServer server = Emote.SERVER;
        Property packedTextures = server.services().sessionService().getPackedTextures(player.getGameProfile());
        if (packedTextures == null) {
            return null;
        }
        MinecraftProfileTextures textures = server.services().sessionService().unpackTextures(packedTextures);
        MinecraftProfileTexture skinTexture = textures.skin();
        if (skinTexture == null) {
            return null;
        }
        boolean slimModel = "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
        return new PlayerSkinSource(
            player.getUUID(),
            player.getGameProfile().name(),
            skinTexture.getHash(),
            skinTexture.getUrl(),
            slimModel
        );
    }

}
