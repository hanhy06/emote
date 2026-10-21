package io.github.hanhy06.emote.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteLoadException;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.PreparedSequence;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.content.loader.SequenceJsonParser;
import io.github.hanhy06.emote.playback.EmotePlayback;
import io.github.hanhy06.emote.skin.account.*;
import io.github.hanhy06.emote.skin.mineskin.MineSkinClient;
import io.github.hanhy06.emote.skin.mineskin.MineSkinProvider;
import io.github.hanhy06.emote.skin.model.PlayerSkinPart;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import io.github.hanhy06.emote.skin.model.SkinOverride;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.hanhy06.emote.config.Config;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDefaultSkinTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, 0, 1);
    private static final PlayerSkinRegion BODY = new PlayerSkinRegion(PlayerSkinPart.BODY, 0, 1);
    private static HolderLookup.Provider registries;
    @TempDir Path directory;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void persistsAndReplacesEmbeddedPngWithoutLosingOtherJsonFields() throws Exception {
        Path path = this.directory.resolve("first.json");
        JsonObject json = animationJson("test:first");
        json.add("skin", JsonParser.parseString("{\"extension\":{\"keep\":true}}"));
        json.addProperty("custom", "keep");
        Files.writeString(path, json.toString());
        byte[] first = png(0xff125678), second = png(0xffabcdef);
        EmoteDirectoryLoader.updateDefaultSkin(path, "test:first", Base64.getEncoder().encodeToString(first));
        var loaded = new AnimationJsonParser().parse(path);
        assertArrayEquals(first, loaded.model().skin().defaultSource().png());
        var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals("keep", saved.get("custom").getAsString());
        assertTrue(saved.getAsJsonObject("skin").getAsJsonObject("extension").get("keep").getAsBoolean());
        assertFalse(Files.exists(path.resolveSibling("first.json.tmp")));

        EmoteDirectoryLoader.updateDefaultSkin(path, "test:first", Base64.getEncoder().encodeToString(second));
        assertArrayEquals(second, new AnimationJsonParser().parse(path).model().skin().defaultSource().png());
        String retained = Files.readString(path);
        assertThrows(java.io.IOException.class, () -> EmoteDirectoryLoader.updateDefaultSkin(path, "test:other", Base64.getEncoder().encodeToString(first)));
        assertEquals(retained, Files.readString(path));
        saved.getAsJsonObject("skin").addProperty("default", "invalid");
        Files.writeString(path, saved.toString());
        assertEquals("$.skin.default", assertThrows(EmoteLoadException.class, () -> new AnimationJsonParser().parse(path)).fieldPath());
    }

    @Test
    void selectsOnlyWholePlayerOrWholeEmbeddedSkinsAcrossAnimations() throws Exception {
        PreparedAnimation first = animation("test:first", png(0xff123456));
        PreparedAnimation second = animation("test:second", png(0xffabcdef));
        PlayerSkinSource a = first.model().skin().defaultSource(), b = second.model().skin().defaultSource();
        PlayerSkinSource player = new PlayerSkinSource(UUID.randomUUID(), "Player", "c".repeat(64), "unused", false);
        var cache = new SkinCache(this.directory.resolve("cache"));
        Map<PlayerSkinRegion, String> fallbackA = Map.of(HEAD, "a-head", BODY, "a-body");
        Map<PlayerSkinRegion, String> fallbackB = Map.of(HEAD, "b-head", BODY, "b-body");
        assertTrue(cache.save(a.textureHash(), false, fallbackA, cache.epoch()));
        assertTrue(cache.save(b.textureHash(), false, fallbackB, cache.epoch()));
        assertTrue(cache.save(player.textureHash(), false, Map.of(HEAD, "player-head"), cache.epoch()));
        var coordinator = coordinator(cache);
        var manager = new PlayerSkinManager(coordinator);
        try {
            var preparation = manager.prepareSkinSource(player, List.of(first, second), false);
            assertTrue(preparation.ready());
            assertEquals(Map.of(first.id(), fallbackA, second.id(), fallbackB), preparation.textures());
            assertEquals(preparation.textures(), manager.prepareSkinSource(null, List.of(first, second), true).textures());

            assertTrue(cache.save(player.textureHash(), false, Map.of(BODY, "player-body"), cache.epoch()));
            var completePlayer = Map.of(HEAD, "player-head", BODY, "player-body");
            assertEquals(Map.of(first.id(), completePlayer, second.id(), completePlayer),
                manager.prepareSkinSource(player, List.of(first, second), false).textures());

            PlayerSkinSource incomplete = PlayerSkinSource.fromPng(png(0xffaabbcc));
            assertTrue(cache.save(incomplete.textureHash(), false, Map.of(HEAD, "partial-default-head"), cache.epoch()));
            second.model().skin().setDefaultSource(incomplete);
            var missingPlayer = new PlayerSkinSource(UUID.randomUUID(), "Missing", "d".repeat(64), "unused", false);
            cache.load(missingPlayer.textureHash(), false, cache.epoch());
            preparation = manager.prepareSkinSource(missingPlayer, List.of(first, second), false);
            assertFalse(preparation.ready());
            assertTrue(preparation.textures().isEmpty());
            assertEquals(completePlayer, manager.prepareSkinSource(player, List.of(first, second), false).textures().get(second.id()));
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void keepsSkinSelectionAlignedWithSequenceWaitTransitionEmptyAndSeek() throws Exception {
        var first = animation("test:first", png(0xff123456));
        var second = animation("test:second", png(0xffabcdef));
        JsonObject sequence = animationJson("test:sequence");
        sequence.addProperty("type", "sequence");
        sequence.remove("nodes");
        sequence.remove("animation");
        sequence.add("settings", JsonParser.parseString("""
            {"cooldown":"0t","player":{"hidden":true,"stop_conditions":{"movement_distance":0.3,
             "jump":true,"submerge":true,"ride":true,"damage":true,"attack":true,"game_mode_change":true}}}
            """));
        sequence.add("steps", JsonParser.parseString("""
            [{"emote":"test:first"},{"wait":"2t"},{"emote":"test:second","transition":"2t"},
             {"emote":"emote:empty"},{"emote":"test:first"}]
            """));
        Path path = this.directory.resolve("sequence.json");
        Files.writeString(path, sequence.toString());
        var prepared = PreparedSequence.prepare(new SequenceJsonParser().parse(path).model(), Map.of(first.id(), first, second.id(), second));
        assertEquals(Set.of(first, second), Set.copyOf(prepared.animations()));
        var playback = new EmotePlayback(prepared);
        assertEquals(first.id(), apply(playback.prepareStart(new Target())).skinAnimationId());
        assertEquals(first.id(), apply(playback.prepareAdvance(2)).skinAnimationId());
        assertEquals(second.id(), apply(playback.prepareAdvance(2)).skinAnimationId());
        assertEquals(first.id(), apply(playback.prepareStep(4, 0, 1)).skinAnimationId());
        assertNull(apply(playback.prepareStep(3, 0, 0)).skinAnimationId());
        assertEquals(second.id(), apply(playback.prepareStep(2, 0, 0)).skinAnimationId());
    }

    @Test
    void retainsPreviousDefaultsUntilEveryReplacementRegionIsReady() throws Exception {
        var first = animation("test:first", png(0xff123456));
        var second = animation("test:second", png(0xffabcdef));
        PlayerSkinSource originalA = first.model().skin().defaultSource(), originalB = second.model().skin().defaultSource();
        PlayerSkinSource replacement = PlayerSkinSource.fromPng(png(0xffaabbcc));
        var cache = new SkinCache(this.directory.resolve("cache"));
        assertTrue(cache.save(replacement.textureHash(), false, Map.of(HEAD, "head"), cache.epoch()));
        var coordinator = coordinator(cache);
        var manager = new PlayerSkinManager(coordinator);
        var source = PlayerSkinManager.class.getDeclaredField("configuredDefault");
        source.setAccessible(true);
        source.set(manager, replacement);
        var pending = PlayerSkinManager.class.getDeclaredField("pendingDefaults");
        pending.setAccessible(true);
        pending.set(manager, List.of(first, second));
        var publish = PlayerSkinManager.class.getDeclaredMethod("publishDefault");
        publish.setAccessible(true);
        try {
            assertEquals(false, publish.invoke(manager));
            assertSame(originalA, first.model().skin().defaultSource());
            assertSame(originalB, second.model().skin().defaultSource());
            assertTrue(cache.save(replacement.textureHash(), false, Map.of(BODY, "body"), cache.epoch()));
            assertEquals(true, publish.invoke(manager));
            assertSame(replacement, first.model().skin().defaultSource());
            assertSame(replacement, second.model().skin().defaultSource());
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void normalizesDownloadedSlimSkinAndBakesEmbeddedPngThroughTheCommonContentCache() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(44, 20, 0xffff0000);
        image.setRGB(45, 20, 0xff00ff00);
        image.setRGB(46, 20, 0xff0000ff);
        var downloads = new AtomicInteger();
        var client = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String url) {
                assertEquals(0, downloads.getAndIncrement());
                return image;
            }
        };
        var cache = new SkinCache(this.directory.resolve("cache"));
        var accounts = new MinecraftAccountManager(new AccountCredentialStore(this.directory.resolve("unused-accounts.bin")), new MinecraftAccountClient());
        var mineSkin = new MineSkinProvider(cache, new MineSkinClient(java.net.http.HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress("127.0.0.1", 9)))
            .connectTimeout(java.time.Duration.ofMillis(250)).build()));
        Config config = Config.createDefault();
        mineSkin.configure(new Config(config.schemaVersion(), config.menuPageSize(), "test-key", config.mineSkinPollIntervalSeconds(),
            config.mineSkinCacheRetentionDays(), config.mineSkinCacheMaxMiB(), config.maxActiveDisplayEntities(), ""));
        var baker = new PlayerSkinBaker();
        var coordinator = new SkinBakeCoordinator(accounts, baker, client, cache, new AccountBakeQueue(accounts, client), mineSkin);
        var ready = new LinkedBlockingQueue<UUID>();
        coordinator.setListener(new SkinBakeCoordinator.Listener() {
            public void onReady(UUID player, long generation) { ready.add(player); }
        });
        try {
            var remote = new PlayerSkinSource(UUID.randomUUID(), "Player", "a".repeat(64), "remote", true);
            var embedded = coordinator.embed(remote).get(5, TimeUnit.SECONDS);
            var normalized = ImageIO.read(new java.io.ByteArrayInputStream(embedded.png()));
            assertFalse(embedded.slimModel());
            assertEquals(0xff0000ff, normalized.getRGB(47, 20));
            var arm = new PlayerSkinRegion(PlayerSkinPart.RIGHT_ARM, 0, 1);
            byte[] baked = baker.bake(baker.prepare(normalized, false), arm);
            assertTrue(cache.saveContent(SkinCache.createContentKey(baked, false), "cached-arm", cache.epoch()));
            coordinator.prepare(embedded, Set.of(arm));
            assertEquals(embedded.playerUuid(), ready.poll(5, TimeUnit.SECONDS));
            assertEquals(Map.of(arm, "cached-arm"), coordinator.prepare(embedded, Set.of(arm)).textures());
            var overridden = embedded.withOverride(new SkinOverride(png(0xffabcdef), png(0xffffffff)));
            var expected = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 64; x++) {
                if (y >= 8 || x >= 8 && x < 24 || x >= 40 && x < 56) expected.setRGB(x, y, 0xffabcdef);
            }
            var encoded = new ByteArrayOutputStream();
            ImageIO.write(expected, "png", encoded);
            assertTrue(cache.saveContent(SkinCache.createContentKey(encoded.toByteArray(), false), "overridden-arm", cache.epoch()));
            coordinator.prepare(overridden, Set.of(arm));
            assertEquals(embedded.playerUuid(), ready.poll(5, TimeUnit.SECONDS));
            assertEquals(Map.of(arm, "overridden-arm"), coordinator.prepare(overridden, Set.of(arm)).textures());
            assertEquals(Map.of(arm, "cached-arm"), coordinator.prepare(embedded, Set.of(arm)).textures());
            assertEquals(1, downloads.get());
            assertFalse(Files.exists(this.directory.resolve("cache/default_skin.json")));
        } finally { coordinator.cancelPendingBakes(); }
    }

    @Test
    void readsOverrideAndRetainsItWhenTheServerReplacesTheEmbeddedDefault() throws Exception {
        Path path = this.directory.resolve("override.json");
        JsonObject json = animationJson("test:override");
        JsonObject override = new JsonObject();
        override.addProperty("texture", Base64.getEncoder().encodeToString(png(0xffabcdef)));
        override.addProperty("mask", Base64.getEncoder().encodeToString(png(0xffffffff)));
        JsonObject skin = new JsonObject();
        skin.add("override", override);
        json.add("skin", skin);
        Files.writeString(path, json.toString());
        var loaded = new AnimationJsonParser().parse(path).model();
        assertNull(loaded.skin().defaultSource());
        String hash = loaded.skin().override().hash();
        EmoteDirectoryLoader.updateDefaultSkin(path, "test:override", Base64.getEncoder().encodeToString(png(0xff123456)));
        loaded = new AnimationJsonParser().parse(path).model();
        assertNotNull(loaded.skin().defaultSource());
        assertEquals(hash, loaded.skin().override().hash());
        assertEquals(override, JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("skin").getAsJsonObject("override"));

        override.remove("mask");
        Files.writeString(path, json.toString());
        assertEquals("$.skin.override.mask", assertThrows(EmoteLoadException.class, () -> new AnimationJsonParser().parse(path)).fieldPath());
        override.addProperty("mask", Base64.getEncoder().encodeToString(png(0xff888888)));
        Files.writeString(path, json.toString());
        assertEquals("$.skin.override", assertThrows(EmoteLoadException.class, () -> new AnimationJsonParser().parse(path)).fieldPath());
    }

    @Test
    void selectsWholeOverrideSetsAndRestoresThePlainSkinWhenChangingSequenceSteps() throws Exception {
        var first = animation("test:first", png(0xff123456));
        var json = JsonParser.parseString(Files.readString(first.sourcePath())).getAsJsonObject();
        var override = new JsonObject();
        override.addProperty("texture", Base64.getEncoder().encodeToString(png(0xffaabbcc)));
        override.addProperty("mask", Base64.getEncoder().encodeToString(png(0xffffffff)));
        json.getAsJsonObject("skin").add("override", override);
        Files.writeString(first.sourcePath(), json.toString());
        first = PreparedAnimation.prepare(new AnimationJsonParser().parse(first.sourcePath()), registries);
        var second = animation("test:second", png(0xffabcdef));
        var player = new PlayerSkinSource(UUID.randomUUID(), "Player", "c".repeat(64), "unused", false);
        PlayerSkinSource playerOverride = player.withOverride(first.model().skin().override());
        PlayerSkinSource fallbackOverride = first.model().skin().defaultSource().withOverride(first.model().skin().override());
        var cache = new SkinCache(this.directory.resolve("cache"));
        var own = Map.of(HEAD, "own-head", BODY, "own-body");
        var dressed = Map.of(HEAD, "dressed-head", BODY, "dressed-body");
        var defaultDressed = Map.of(HEAD, "default-dressed-head", BODY, "default-dressed-body");
        var plainDefault = Map.of(HEAD, "default-head", BODY, "default-body");
        assertTrue(cache.save(player.textureHash(), false, own, cache.epoch()));
        assertTrue(cache.save(playerOverride.textureHash(), false, Map.of(HEAD, "dressed-head"), cache.epoch()));
        assertTrue(cache.save(fallbackOverride.textureHash(), false, defaultDressed, cache.epoch()));
        assertTrue(cache.save(second.model().skin().defaultSource().textureHash(), false, plainDefault, cache.epoch()));
        var coordinator = coordinator(cache);
        var manager = new PlayerSkinManager(coordinator);
        try {
            var preparation = manager.prepareSkinSource(player, List.of(first, second), false);
            assertTrue(preparation.ready());
            assertEquals(Map.of(first.id(), defaultDressed, second.id(), plainDefault), preparation.textures());
            assertTrue(cache.save(playerOverride.textureHash(), false, Map.of(BODY, "dressed-body"), cache.epoch()));
            preparation = manager.prepareSkinSource(player, List.of(first, second), false);
            assertTrue(preparation.ready());
            assertEquals(Map.of(first.id(), dressed, second.id(), own), preparation.textures());

            JsonObject sequence = animationJson("test:sequence");
            sequence.addProperty("type", "sequence");
            sequence.remove("nodes");
            sequence.remove("animation");
            sequence.add("settings", JsonParser.parseString("""
                {"cooldown":"0t","player":{"hidden":true,"stop_conditions":{"movement_distance":0.3,
                 "jump":true,"submerge":true,"ride":true,"damage":true,"attack":true,"game_mode_change":true}}}
                """));
            sequence.add("steps", JsonParser.parseString("[{\"emote\":\"test:first\"},{\"wait\":\"2t\"},{\"emote\":\"test:second\",\"transition\":\"2t\"}]"));
            Path path = this.directory.resolve("sequence.json");
            Files.writeString(path, sequence.toString());
            var prepared = PreparedSequence.prepare(new SequenceJsonParser().parse(path).model(), Map.of(first.id(), first, second.id(), second));
            var playback = new EmotePlayback(prepared);
            assertEquals(dressed, preparation.textures().get(apply(playback.prepareStart(new Target())).skinAnimationId()));
            assertEquals(dressed, preparation.textures().get(apply(playback.prepareAdvance(2)).skinAnimationId()));
            assertEquals(own, preparation.textures().get(apply(playback.prepareAdvance(2)).skinAnimationId()));
            assertEquals(dressed, preparation.textures().get(apply(playback.prepareStep(0, 0, 1)).skinAnimationId()));
            assertEquals(own, preparation.textures().get(apply(playback.prepareStep(2, 0, 1)).skinAnimationId()));

            var replacement = PlayerSkinSource.fromPng(png(0xff998877));
            var replacementOverride = replacement.withOverride(first.model().skin().override());
            assertTrue(cache.save(replacement.textureHash(), false, own, cache.epoch()));
            cache.load(replacementOverride.textureHash(), false, cache.epoch());
            var sourceField = PlayerSkinManager.class.getDeclaredField("configuredDefault");
            sourceField.setAccessible(true);
            sourceField.set(manager, replacement);
            var pending = PlayerSkinManager.class.getDeclaredField("pendingDefaults");
            pending.setAccessible(true);
            pending.set(manager, List.of(first, second));
            var publish = PlayerSkinManager.class.getDeclaredMethod("publishDefault");
            publish.setAccessible(true);
            assertEquals(false, publish.invoke(manager));
            assertNotSame(replacement, first.model().skin().defaultSource());
            assertNotSame(replacement, second.model().skin().defaultSource());
            assertTrue(cache.save(replacementOverride.textureHash(), false, dressed, cache.epoch()));
            assertEquals(true, publish.invoke(manager));
            assertSame(replacement, first.model().skin().defaultSource());
            assertSame(replacement, second.model().skin().defaultSource());
            assertEquals(Map.of(first.id(), dressed, second.id(), own), manager.prepareSkinSource(null, List.of(first, second), false).textures());
        } finally { coordinator.cancelPendingBakes(); }
    }

    private PreparedAnimation animation(String id, byte[] png) throws Exception {
        Path path = this.directory.resolve(id.substring(id.indexOf(':') + 1) + ".json");
        Files.writeString(path, animationJson(id).toString());
        EmoteDirectoryLoader.updateDefaultSkin(path, id, Base64.getEncoder().encodeToString(png));
        return PreparedAnimation.prepare(new AnimationJsonParser().parse(path), registries);
    }

    private static JsonObject animationJson(String id) {
        JsonObject json = JsonParser.parseString("""
            {"type":"animation","schema_version":5,"id":"test:placeholder","metadata":{"name":"Skin","description":""},
             "nodes":{"head":{"attachments":{"skin":{"type":"player_skin","part":"head","region":{"from":0,"to":1}}}},
                      "body":{"attachments":{"skin":{"type":"player_skin","part":"body","region":{"from":0,"to":1}}}}},
             "animation":{"duration":"2t","tracks":[]}}
            """).getAsJsonObject();
        json.addProperty("id", id);
        return json;
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) image.setRGB(x, y, color);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private SkinBakeCoordinator coordinator(SkinCache cache) {
        var accounts = new MinecraftAccountManager(new AccountCredentialStore(this.directory.resolve("unused-accounts.bin")), new MinecraftAccountClient());
        var client = new MinecraftSkinClient() {
            @Override public BufferedImage downloadSkin(String url) { throw new AssertionError("Cached skin preparation must not download"); }
        };
        return new SkinBakeCoordinator(accounts, new PlayerSkinBaker(), client, cache,
            new AccountBakeQueue(accounts, client), new MineSkinProvider(cache, new MineSkinClient()));
    }

    private static EmotePlayback.Frame apply(EmotePlayback.Frame frame) {
        frame.calculate();
        frame.apply();
        frame.dispatchEvents();
        return frame;
    }

    private static final class Target implements EmotePlayback.PoseTarget {
        public Matrix4fc rootMatrix() { return new Matrix4f(); }
        public Transformation createTransformation(String id, Matrix4fc matrix) { return new Transformation(matrix); }
        public void applyTransform(String id, Transformation transformation, int interpolationDurationTicks) {}
        public void setVisible(String id, boolean visible) {}
        public void setVisible(String id, String attachment, boolean visible) {}
        public void applyNbt(String id, String attachment, CompoundTag nbt, Set<String> remove) {}
        public void resetAll() {}
    }
}
