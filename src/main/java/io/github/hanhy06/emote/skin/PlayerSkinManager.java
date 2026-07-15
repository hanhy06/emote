package io.github.hanhy06.emote.skin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.playback.PlaybackNodes.NodeInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Consumer;

public class PlayerSkinManager implements ConfigListener {
    private static final long PENDING_JOB_MAX_AGE_MILLIS = 35L * 60L * 1000L;
    private static final long FAILED_JOB_RETRY_DELAY_MILLIS = 5L * 60L * 1000L;
    private final PlayerSkinBaker playerSkinBaker;
    private final MineSkinTextureStore mineSkinTextureStore;
    private final MineSkinApiClient mineSkinApiClient;
    private final MineSkinBakeExecutor mineSkinBakeExecutor;
    private final Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver;
    private final List<Consumer<UUID>> readyListeners = new CopyOnWriteArrayList<>();

    private volatile String mineSkinApiKey = "";

    public PlayerSkinManager() {
        this(
            new PlayerSkinBaker(),
            new MineSkinTextureStore(),
            new MineSkinApiClient(),
            new MineSkinBakeExecutor(),
            PlayerSkinManager::readPlayerSkinSource
        );
    }

    PlayerSkinManager(
        PlayerSkinBaker playerSkinBaker,
        MineSkinTextureStore mineSkinTextureStore,
        MineSkinApiClient mineSkinApiClient,
        MineSkinBakeExecutor mineSkinBakeExecutor,
        Function<ServerPlayer, PlayerSkinSource> playerSkinSourceResolver
    ) {
        this.playerSkinBaker = Objects.requireNonNull(playerSkinBaker, "playerSkinBaker");
        this.mineSkinTextureStore = Objects.requireNonNull(mineSkinTextureStore, "mineSkinTextureStore");
        this.mineSkinApiClient = Objects.requireNonNull(mineSkinApiClient, "mineSkinApiClient");
        this.mineSkinBakeExecutor = Objects.requireNonNull(mineSkinBakeExecutor, "mineSkinBakeExecutor");
        this.playerSkinSourceResolver = Objects.requireNonNull(playerSkinSourceResolver, "playerSkinSourceResolver");
    }

    @Override
    public void onConfigReload(Config newConfig) {
        this.mineSkinApiKey = newConfig.mineSkinApiKey();
        this.mineSkinApiClient.setJobPollIntervalSeconds(newConfig.mineSkinPollIntervalSeconds());
    }

    public PreparedPlayerSkin preparePlayerSkin(ServerPlayer player, List<EmoteSkinPart> skinParts) {
        if (skinParts.isEmpty()) {
            return null;
        }
        return preparePlayerSkin(player, createTextureKeys(skinParts));
    }

    private PreparedPlayerSkin preparePlayerSkin(
        ServerPlayer player,
        Set<PlayerSkinTextureKey> requiredTextureKeys
    ) {
        if (!MineSkinApiClient.hasApiKey(this.mineSkinApiKey)) {
            return null;
        }
        PlayerSkinSource skinSource = this.playerSkinSourceResolver.apply(player);
        if (skinSource == null) {
            return null;
        }
        Map<PlayerSkinTextureKey, String> savedTextureUrls = loadMineSkinTextureSet(skinSource, requiredTextureKeys);
        if (savedTextureUrls.size() < requiredTextureKeys.size()) {
            scheduleMineSkinBake(skinSource, requiredTextureKeys);
        }
        return savedTextureUrls.isEmpty() ? null : new PreparedPlayerSkin(savedTextureUrls);
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
            if (node != null && node.entity() instanceof Display.ItemDisplay display) {
                applyMineSkinProfile(display, skinPart.skinPart(), skinPart.skinSegment(), preparedPlayerSkin);
            }
        }
    }

    public void addReadyListener(Consumer<UUID> readyListener) {
        this.readyListeners.add(Objects.requireNonNull(readyListener, "readyListener"));
    }

    public void cancelPendingBakes() {
        this.mineSkinBakeExecutor.cancelAll();
    }

    private boolean applyMineSkinProfile(
        Display.ItemDisplay display,
        PlayerSkinPart skinPart,
        PlayerSkinSegment skinSegment,
        PreparedPlayerSkin preparedSkin
    ) {
        String textureUrl = preparedSkin.findTextureUrl(skinPart, skinSegment);
        if (textureUrl == null) {
            return false;
        }
        SlotAccess itemSlot = display.getSlot(0);
        if (itemSlot == null) {
            return false;
        }
        ItemStack itemStack = itemSlot.get();
        if (!itemStack.is(Items.PLAYER_HEAD)) {
            return false;
        }
        ItemStack profileStack = itemStack.copy();
        profileStack.set(DataComponents.PROFILE, PlayerSkinTextureHelper.createProfile(textureUrl));
        return itemSlot.set(profileStack);
    }

    private Set<PlayerSkinTextureKey> createTextureKeys(List<EmoteSkinPart> skinParts) {
        Set<PlayerSkinTextureKey> textureKeys = new LinkedHashSet<>(skinParts.size());
        for (EmoteSkinPart skinPart : skinParts) {
            textureKeys.add(new PlayerSkinTextureKey(skinPart.skinPart(), skinPart.skinSegment()));
        }
        return textureKeys;
    }

    private Map<PlayerSkinTextureKey, String> loadMineSkinTextureSet(
        PlayerSkinSource skinSource,
        Set<PlayerSkinTextureKey> requiredTextureKeys
    ) {
        Map<PlayerSkinTextureKey, String> stored = this.mineSkinTextureStore.load(
            skinSource.textureHash(),
            skinSource.slimModel()
        );
        Map<PlayerSkinTextureKey, String> result = new HashMap<>();
        for (PlayerSkinTextureKey textureKey : requiredTextureKeys) {
            String textureUrl = stored.get(textureKey);
            if (textureUrl != null) {
                result.put(textureKey, textureUrl);
            }
        }
        return Map.copyOf(result);
    }

    private void scheduleMineSkinBake(PlayerSkinSource source, Set<PlayerSkinTextureKey> requiredKeys) {
        String apiKey = this.mineSkinApiKey;
        if (!MineSkinApiClient.hasApiKey(apiKey)) {
            return;
        }
        String pendingKey = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        Set<PlayerSkinTextureKey> requestedKeys = Set.copyOf(requiredKeys);
        this.mineSkinBakeExecutor.submit(
            pendingKey,
            () -> bakeAndSaveMineSkinTextureSet(apiKey, source, requestedKeys)
        );
    }

    private void bakeAndSaveMineSkinTextureSet(
        String apiKey,
        PlayerSkinSource source,
        Set<PlayerSkinTextureKey> requiredKeys
    ) {
        try {
            Map<PlayerSkinTextureKey, String> stored = this.mineSkinTextureStore.load(source.textureHash(), source.slimModel());
            Set<PlayerSkinTextureKey> missingKeys = new LinkedHashSet<>(requiredKeys);
            missingKeys.removeAll(stored.keySet());
            if (missingKeys.isEmpty()) {
                return;
            }
            BufferedImage sourceImage = this.mineSkinApiClient.downloadSkinImage(source.textureUrl());
            Map<PlayerSkinTextureKey, String> saved = new HashMap<>(stored);
            boolean savedAny = false;
            for (PlayerSkinTextureKey textureKey : missingKeys) {
                byte[] bakedImage = this.playerSkinBaker.bake(
                    sourceImage,
                    textureKey.skinPart(),
                    textureKey.skinSegment(),
                    source.slimModel()
                );
                String contentHash = MineSkinContentKey.create(bakedImage, source.slimModel());
                MineSkinTextureResult cachedResult = this.mineSkinTextureStore.loadContent(contentHash);
                String textureUrl;
                if (cachedResult != null) {
                    textureUrl = cachedResult.textureUrl();
                } else {
                    long now = System.currentTimeMillis();
                    if (this.mineSkinTextureStore.isRetryBlocked(contentHash, now)) {
                        continue;
                    }
                    MineSkinTextureStore.MineSkinPendingJob pendingJob = this.mineSkinTextureStore.loadPendingJob(contentHash);
                    if (pendingJob != null && now - pendingJob.submittedAtEpochMillis() > PENDING_JOB_MAX_AGE_MILLIS) {
                        this.mineSkinTextureStore.clearPendingJob(contentHash);
                        pendingJob = null;
                    }
                    try {
                        if (pendingJob != null) {
                            textureUrl = this.mineSkinApiClient.waitForSkinUrl(apiKey, pendingJob.jobId());
                        } else {
                            textureUrl = this.mineSkinApiClient.generateSkinUrl(
                                apiKey,
                                bakedImage,
                                source.slimModel(),
                                jobId -> this.mineSkinTextureStore.savePendingJob(contentHash, jobId)
                            );
                        }
                    } catch (MineSkinJobFailedException exception) {
                        this.mineSkinTextureStore.clearPendingJob(contentHash);
                        this.mineSkinTextureStore.saveFailure(
                            contentHash,
                            exception.getMessage(),
                            now + FAILED_JOB_RETRY_DELAY_MILLIS
                        );
                        Emote.LOGGER.warn("MineSkin rejected baked texture {}: {}", contentHash, exception.getMessage());
                        continue;
                    }
                    this.mineSkinTextureStore.saveContent(contentHash, new MineSkinTextureResult(textureUrl));
                    this.mineSkinTextureStore.clearPendingJob(contentHash);
                    this.mineSkinTextureStore.clearFailure(contentHash);
                }
                saved.put(textureKey, textureUrl);
                this.mineSkinTextureStore.save(source.textureHash(), source.slimModel(), saved);
                savedAny = true;
            }
            Emote.LOGGER.info("Saved MineSkin bake for {} ({})", source.playerName(), source.textureHash());
            if (savedAny) {
                notifySkinReady(source.playerUuid());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Emote.LOGGER.warn("MineSkin bake interrupted for {}", source.playerName(), exception);
        } catch (IOException | IllegalArgumentException exception) {
            Emote.LOGGER.warn("MineSkin bake failed for {}", source.playerName(), exception);
        }
    }

    private void notifySkinReady(UUID playerUuid) {
        MinecraftServer server = Emote.SERVER;
        if (server == null || this.readyListeners.isEmpty()) {
            return;
        }
        server.execute(() -> {
            for (Consumer<UUID> readyListener : this.readyListeners) {
                readyListener.accept(playerUuid);
            }
        });
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

    record PlayerSkinSource(UUID playerUuid, String playerName, String textureHash, String textureUrl, boolean slimModel) {
        PlayerSkinSource {
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(textureHash, "textureHash");
            Objects.requireNonNull(textureUrl, "textureUrl");
        }
    }
}
