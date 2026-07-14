package io.github.hanhy06.emote.skin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.ConfigListener;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.emote.EmoteDatapackNames;
import io.github.hanhy06.emote.emote.EmoteDefinition;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class PlayerSkinManager implements ConfigListener {
    private static final int MAX_SKIN_DOWNLOAD_BYTES = 1_048_576;
    private static final int SKIN_DOWNLOAD_TIMEOUT_MILLIS = 5000;

    private final PlayerSkinBaker playerSkinBaker = new PlayerSkinBaker();
    private final MineSkinTextureStore mineSkinTextureStore = new MineSkinTextureStore();
    private final HttpClient httpClient = MineSkinApiClient.createHttpClient();
    private final MineSkinApiClient mineSkinApiClient = new MineSkinApiClient(this.httpClient);
    private final MineSkinBakeExecutor mineSkinBakeExecutor = new MineSkinBakeExecutor();

    private volatile String mineSkinApiKey = "";

    @Override
    public void onConfigReload(Config newConfig) {
        this.mineSkinApiKey = newConfig.mineSkinApiKey();
        this.mineSkinApiClient.setJobPollIntervalSeconds(newConfig.mineSkinPollIntervalSeconds());
    }

    public PlayerSkinPreparationResult preparePlayerSkin(ServerPlayer player, EmoteDefinition definition) {
        List<EmoteSkinPart> skinParts = definition.skinParts();
        if (skinParts.isEmpty()) {
            return PlayerSkinPreparationResult.ready(null);
        }
        if (!MineSkinApiClient.hasApiKey(this.mineSkinApiKey)) {
            return PlayerSkinPreparationResult.ready(null);
        }
        PlayerSkinSource skinSource = readPlayerSkinSource(player);
        if (skinSource == null) {
            return PlayerSkinPreparationResult.ready(null);
        }
        Set<PlayerSkinTextureKey> requiredTextureKeys = createTextureKeys(skinParts);
        Map<PlayerSkinTextureKey, String> savedTextureUrls = loadMineSkinTextureSet(skinSource, requiredTextureKeys);
        if (savedTextureUrls.size() < requiredTextureKeys.size()) {
            scheduleMineSkinBake(player.getGameProfile().name(), skinSource, requiredTextureKeys);
            return PlayerSkinPreparationResult.failure("Player skin is being prepared. Try again shortly.");
        }
        return PlayerSkinPreparationResult.ready(
            new PreparedPlayerSkin(savedTextureUrls)
        );
    }

    public void applySkinParts(
        ServerPlayer player,
        EmoteDefinition definition,
        PreparedPlayerSkin preparedPlayerSkin,
        UUID rootEntityUuid
    ) {
        if (preparedPlayerSkin == null || definition.skinParts().isEmpty()) {
            return;
        }
        Set<String> requestedTags = new LinkedHashSet<>();
        Map<String, EmoteSkinPart> skinPartByTag = new HashMap<>();
        for (EmoteSkinPart skinPart : definition.skinParts()) {
            String requestedTag = EmoteDatapackNames.partTag(definition.namespace(), skinPart.partIndex());
            requestedTags.add(requestedTag);
            skinPartByTag.put(requestedTag, skinPart);
        }

        Set<String> appliedTags = new HashSet<>();
        Set<Integer> visitedEntityIds = new HashSet<>();
        Entity rootEntity = player.level().getEntity(rootEntityUuid);
        if (rootEntity == null) {
            return;
        }
        applySkinPartsInTree(
            rootEntity,
            requestedTags,
            skinPartByTag,
            preparedPlayerSkin,
            appliedTags,
            visitedEntityIds
        );
        if (appliedTags.size() != requestedTags.size()) {
            Emote.LOGGER.warn(
                "Applied player skin to {}/{} parts for {}",
                appliedTags.size(),
                requestedTags.size(),
                definition.namespace()
            );
        }
    }

    private void applySkinPartsInTree(
        Entity entity,
        Set<String> requestedTags,
        Map<String, EmoteSkinPart> skinPartByTag,
        PreparedPlayerSkin preparedPlayerSkin,
        Set<String> appliedTags,
        Set<Integer> visitedEntityIds
    ) {
        if (!visitedEntityIds.add(entity.getId())) {
            return;
        }
        if (entity instanceof Display.ItemDisplay display) {
            String tag = findRequestedTag(display.entityTags(), requestedTags);
            if (tag != null && !appliedTags.contains(tag)) {
                EmoteSkinPart skinPart = skinPartByTag.get(tag);
                if (skinPart != null && applyMineSkinProfile(display, skinPart, preparedPlayerSkin)) {
                    appliedTags.add(tag);
                }
            }
        }
        if (appliedTags.size() == requestedTags.size()) {
            return;
        }
        for (Entity passenger : entity.getPassengers()) {
            applySkinPartsInTree(
                passenger,
                requestedTags,
                skinPartByTag,
                preparedPlayerSkin,
                appliedTags,
                visitedEntityIds
            );
        }
    }

    public void clear() {
        this.mineSkinBakeExecutor.clear();
    }

    private boolean applyMineSkinProfile(Display.ItemDisplay display, EmoteSkinPart skinPart, PreparedPlayerSkin preparedSkin) {
        String textureUrl = preparedSkin.findTextureUrl(skinPart.skinPart(), skinPart.skinSegment());
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

    private void scheduleMineSkinBake(String playerName, PlayerSkinSource source, Set<PlayerSkinTextureKey> requiredKeys) {
        String apiKey = this.mineSkinApiKey;
        if (!MineSkinApiClient.hasApiKey(apiKey)) {
            return;
        }
        String pendingKey = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        Set<PlayerSkinTextureKey> requestedKeys = Set.copyOf(requiredKeys);
        this.mineSkinBakeExecutor.submit(
            pendingKey,
            () -> bakeAndSaveMineSkinTextureSet(apiKey, playerName, source, requestedKeys)
        );
    }

    private void bakeAndSaveMineSkinTextureSet(
        String apiKey,
        String playerName,
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
            BufferedImage sourceImage = downloadSkinImage(source.textureUrl());
            Map<PlayerSkinTextureKey, String> saved = new HashMap<>(stored);
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
                    String pendingJobId = this.mineSkinTextureStore.loadPendingJob(contentHash);
                    if (pendingJobId != null) {
                        textureUrl = this.mineSkinApiClient.waitForSkinUrl(apiKey, pendingJobId);
                    } else {
                        textureUrl = this.mineSkinApiClient.generateSkinUrl(
                            apiKey,
                            bakedImage,
                            source.slimModel(),
                            jobId -> this.mineSkinTextureStore.savePendingJob(contentHash, jobId)
                        );
                    }
                    this.mineSkinTextureStore.saveContent(contentHash, new MineSkinTextureResult(textureUrl));
                    this.mineSkinTextureStore.clearPendingJob(contentHash);
                }
                saved.put(textureKey, textureUrl);
                this.mineSkinTextureStore.save(source.textureHash(), source.slimModel(), saved);
            }
            Emote.LOGGER.info("Saved MineSkin bake for {} ({})", playerName, source.textureHash());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Emote.LOGGER.warn("MineSkin bake interrupted for {}", playerName, exception);
        } catch (IOException | IllegalArgumentException exception) {
            Emote.LOGGER.warn("MineSkin bake failed for {}", playerName, exception);
        }
    }

    private PlayerSkinSource readPlayerSkinSource(ServerPlayer player) {
        MinecraftServer server = server();
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
        return new PlayerSkinSource(skinTexture.getHash(), skinTexture.getUrl(), slimModel);
    }

    private BufferedImage downloadSkinImage(String textureUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(textureUrl))
            .timeout(Duration.ofMillis(SKIN_DOWNLOAD_TIMEOUT_MILLIS))
            .GET()
            .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] imageBytes;
        try (InputStream input = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("unexpected skin response: " + response.statusCode());
            }
            imageBytes = input.readNBytes(MAX_SKIN_DOWNLOAD_BYTES + 1);
            if (imageBytes.length > MAX_SKIN_DOWNLOAD_BYTES) {
                throw new IOException("skin image exceeds maximum size");
            }
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("skin image decode failed");
        }
        return image;
    }

    private String findRequestedTag(Set<String> entityTags, Set<String> requestedTags) {
        return entityTags.stream().filter(requestedTags::contains).findFirst().orElse(null);
    }

    private MinecraftServer server() {
        return Emote.SERVER;
    }

    private record PlayerSkinSource(String textureHash, String textureUrl, boolean slimModel) {
        private PlayerSkinSource {
            Objects.requireNonNull(textureHash, "textureHash");
            Objects.requireNonNull(textureUrl, "textureUrl");
        }
    }
}
