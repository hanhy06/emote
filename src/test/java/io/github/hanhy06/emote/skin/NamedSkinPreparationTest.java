package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NamedSkinPreparationTest {
    private static final PlayerSkinRegion HEAD = new PlayerSkinRegion(PlayerSkinPart.HEAD, PlayerSkinSegment.FULL);
    private static final List<SkinBinding> BINDINGS = List.of(new SkinBinding("head", HEAD));

    @Test
    void waitsForNameResolutionAndSharesItAcrossMarkersWithoutAnOnlinePlayer() {
        Provider provider = new Provider();
        CompletableFuture<PlayerSkinSource> lookup = new CompletableFuture<>();
        AtomicInteger requests = new AtomicInteger();
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> { fail("Must not resolve an online player"); return null; },
            name -> { requests.incrementAndGet(); return lookup; });
        assertTrue(manager.prepareNamedSkin(" TestPlayer ", BINDINGS).preparing());
        assertTrue(manager.prepareNamedSkin("testplayer", BINDINGS).preparing());
        assertNull(provider.source);
        assertEquals(1, requests.get());
        PlayerSkinSource source = new PlayerSkinSource(UUID.randomUUID(), "TestPlayer", "texture", "https://textures.example/skin", true);
        lookup.complete(source);
        PlayerSkinPreparation ready = manager.prepareNamedSkin("TESTPLAYER", BINDINGS);
        assertEquals(PlayerSkinPreparation.State.READY, ready.state());
        assertEquals("personal", ready.preparedPlayerSkin().findTextureUrl(HEAD));
        assertSame(source, provider.source);
        assertEquals(Set.of(HEAD), provider.regions);
    }

    @Test
    void missingOrFailedNameUsesDefaultAndDoesNotRepeatTheLookup() {
        Provider provider = new Provider();
        AtomicInteger requests = new AtomicInteger();
        PlayerSkinManager manager = new PlayerSkinManager(provider, ignored -> null, name -> {
            requests.incrementAndGet();
            return name.equals("Missing") ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("Lookup unavailable"));
        });
        for (String name : List.of("Missing", "Failed", "Missing", "Failed")) {
            PlayerSkinPreparation fallback = manager.prepareNamedSkin(name, BINDINGS);
            assertEquals(PlayerSkinPreparation.State.UNAVAILABLE, fallback.state());
            assertEquals("default", fallback.preparedPlayerSkin().findTextureUrl(HEAD));
        }
        assertEquals(2, requests.get());
        assertNull(provider.source);
    }

    @Test
    void propsAndUnspecifiedSkinDoNotStartAProfileLookup() {
        PlayerSkinManager manager = new PlayerSkinManager(new Provider(), ignored -> null, name -> {
            fail("No profile lookup needed"); return null;
        });
        assertEquals(PlayerSkinPreparation.State.READY, manager.prepareNamedSkin("TestPlayer", List.of()).state());
        assertEquals("default", manager.prepareNamedSkin("", BINDINGS).preparedPlayerSkin().findTextureUrl(HEAD));
    }

    private static final class Provider implements PlayerSkinProvider {
        private PlayerSkinSource source;
        private Set<PlayerSkinRegion> regions;
        public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> regions) {
            this.source = source;
            this.regions = regions;
            return new PlayerSkinPreparation(new PreparedPlayerSkin(Map.of(HEAD, "personal")), PlayerSkinPreparation.State.READY, 100);
        }
        public PreparedPlayerSkin defaultSkin() { return new PreparedPlayerSkin(Map.of(HEAD, "default")); }
        public void setListener(Listener listener) {}
        public void cancelPendingBakes() {}
        public void onConfigReload(Config config) {}
    }
}
