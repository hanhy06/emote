package io.github.hanhy06.emote.skin;

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
    private final MineSkinManager mineSkinManager;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();

    public PlayerSkinManager() {
        this(
            new PlayerSkinBaker(),
            new MineSkinCache(),
            new MineSkinClient(),
            new MineSkinGenerationQueue(),
            PlayerSkinManager::readPlayerSkinSource
        );
    }

    PlayerSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinCache mineSkinCache,
        MineSkinClient mineSkinClient,
        MineSkinGenerationQueue generationQueue,
        Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver
    ) {
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
        this.mineSkinManager = new MineSkinManager(
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

    public SkinPreparation preparePlayerSkin(ServerPlayer player, List<EmoteSkinPart> skinParts) {
        if (skinParts.isEmpty()) {
            return new SkinPreparation(null, SkinPreparationState.READY, 100);
        }
        Set<PlayerSkinTextureKey> requiredTextureKeys = new LinkedHashSet<>(skinParts.size());
        for (EmoteSkinPart skinPart : skinParts) {
            requiredTextureKeys.add(new PlayerSkinTextureKey(skinPart.skinPart(), skinPart.skinSegment()));
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return new SkinPreparation(null, SkinPreparationState.UNAVAILABLE, 0);
        }
        return this.mineSkinManager.prepare(skinSource, requiredTextureKeys);
    }

    public void applySkinParts(
        Map<String, NodeInstance> nodes,
        List<EmoteSkinPart> skinParts,
        PreparedPlayerSkin preparedPlayerSkin
    ) {
        if (preparedPlayerSkin == null || skinParts.isEmpty()) {
            return;
        }
        for (EmoteSkinPart skinPart : skinParts) {
            NodeInstance node = nodes.get(skinPart.nodeId());
            if (node != null) {
                applyMineSkinProfile(node, skinPart.skinPart(), skinPart.skinSegment(), preparedPlayerSkin);
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
        PlayerSkinPart skinPart,
        PlayerSkinSegment skinSegment,
        PreparedPlayerSkin preparedSkin
    ) {
        String textureUrl = preparedSkin.findTextureUrl(skinPart, skinSegment);
        if (textureUrl == null) {
            return;
        }
        if (!(node.entity() instanceof Display.ItemDisplay itemDisplay)
            || !(node.displayContent() instanceof ItemContent(ItemStack itemStack))
            || !itemStack.is(Items.PLAYER_HEAD)) {
            return;
        }
        ItemStack profileStack = itemStack.copy();
        profileStack.set(DataComponents.PROFILE, PlayerSkinTextureHelper.createProfile(textureUrl));
        node.setItemStack(profileStack);

        SlotAccess itemSlot = itemDisplay.getSlot(0);
        if (itemSlot.get().isEmpty()) {
            return;
        }
        itemSlot.set(profileStack);
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            return;
        }
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
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("Player skin preparation failed. Try again later."));
            }
        });
    }

    public enum SkinPreparationState {
        READY,
        PREPARING,
        FAILED,
        UNAVAILABLE
    }

    public record SkinPreparation(
        PreparedPlayerSkin preparedPlayerSkin,
        SkinPreparationState state,
        int progressPercent
    ) {
        public SkinPreparation {
            Objects.requireNonNull(state, "state");
            if (progressPercent < 0 || progressPercent > 100) {
                throw new IllegalArgumentException("progressPercent must be between 0 and 100");
            }
        }

        public boolean preparing() {
            return this.state == SkinPreparationState.PREPARING;
        }
    }

    private static PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        MinecraftServer server = Emote.SERVER;
        if (server == null) {
            return null;
        }
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

    record PlayerSkinSource(UUID playerUuid, String playerName, String textureHash, String textureUrl,
                            boolean slimModel) {
        PlayerSkinSource {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(textureHash, "textureHash");
            Objects.requireNonNull(textureUrl, "textureUrl");
        }
    }
}
