package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlayResultFixture;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.content.EmoteCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.content.PreparedEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class EmotePlayServiceTest {
    @Test
    void defaultsPlaybackToActorPlacement() {
        EmoteCatalog catalog = catalogWithWave(0);
        EmotePlayService service = new EmotePlayService(catalog, allowedPolicy(new AtomicLong(), catalog),
            (player, definition, placement) -> {
                assertEquals(PlaybackPlacement.actor(), placement);
                return PlayResultFixture.SUCCESS;
            },
            (player, definition, source) -> null);
        assertTrue(service.play(null, "demo:wave").isSuccess());
    }

    @Test
    void forwardsExternalPlacementThroughTheNormalPlaybackPolicyAndEvents() {
        EmoteCatalog catalog = catalogWithWave(0);
        PlaybackPlacement placement = PlaybackPlacement.external(new Vec3(12, 64, -4), 90);
        AtomicInteger events = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(catalog, allowedPolicy(new AtomicLong(), catalog),
            (player, definition, received) -> {
                assertSame(placement, received);
                assertEquals("demo:wave", definition.id());
                assertEquals(1, events.get());
                return PlayResultFixture.SUCCESS;
            },
            (player, definition, source) -> {
                assertEquals(PlaySource.API, source);
                events.incrementAndGet();
                return null;
            });
        assertTrue(service.play(null, "demo:wave", PlaySource.API, placement).isSuccess());
    }

    @Test
    void rejectsUnknownIdsBeforeEvaluatingPlaybackPolicy() {
        EmotePlayService service = new EmotePlayService(
            new EmoteCatalog(),
            policy((ignoredPlayer, ignoredPermission, ignoredDefault) -> {
                throw new AssertionError("Unknown IDs must not reach playback policy");
            }, new AtomicLong()),
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> PlayResultFixture.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertHasErrorMessage(service.play(null, "demo:missing"));
    }

    @Test
    void policyRejectionPreventsEventsAndPlayback() {
        EmoteCatalog catalog = catalogWithWave(0);
        PlaybackPolicyService policy = policy(
            (ignoredPlayer, ignoredPermission, ignoredDefault) -> false,
            new AtomicLong()
        );
        policy.onAccessConfigReload(new AccessConfig(List.of(), List.of()));
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> {
                throw new AssertionError("Rejected playback must not start");
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> {
                throw new AssertionError("Rejected playback must not dispatch events");
            }
        );

        assertHasErrorMessage(service.play(null, "demo:wave"));
    }

    @Test
    void listenerCancellationPreventsPlaybackAndCooldown() {
        AtomicLong tick = new AtomicLong();
        EmoteCatalog catalog = catalogWithWave(20);
        PlaybackPolicyService policy = allowedPolicy(tick, catalog);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> {
                starts.incrementAndGet();
                return PlayResultFixture.SUCCESS;
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> Component.literal("Cancelled")
        );

        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertEquals(0, starts.get());
    }

    @Test
    void playbackFailureDoesNotStartCooldown() {
        AtomicLong tick = new AtomicLong();
        EmoteCatalog catalog = catalogWithWave(20);
        PlaybackPolicyService policy = allowedPolicy(tick, catalog);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> {
                starts.incrementAndGet();
                return PlayResult.failure("Unavailable");
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertEquals(2, starts.get());
    }

    @Test
    void playbackExceptionReleasesClaimedCooldown() {
        AtomicLong tick = new AtomicLong();
        EmoteCatalog catalog = catalogWithWave(20);
        PlaybackPolicyService policy = allowedPolicy(tick, catalog);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> {
                if (starts.getAndIncrement() == 0) {
                    throw new IllegalStateException("Failed to start");
                }
                return PlayResultFixture.SUCCESS;
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertThrows(IllegalStateException.class, () -> service.play(null, "demo:wave"));
        assertTrue(service.play(null, "demo:wave").isSuccess());
    }

    @Test
    void successfulPlaybackCannotRestartBeforeItsCooldownBegins() {
        AtomicLong tick = new AtomicLong();
        EmoteCatalog catalog = catalogWithWave(20);
        PlaybackCooldownService cooldowns = cooldowns(tick);
        PlaybackPolicyService policy = allowedPolicy(catalog, cooldowns);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> {
                starts.incrementAndGet();
                return PlayResultFixture.SUCCESS;
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertTrue(service.play(null, "demo:wave").isSuccess());
        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertEquals(1, starts.get());

        cooldowns.onPlaybackEnded(null, "demo:wave");
        assertHasErrorMessage(service.play(null, "demo:wave"));
    }

    @Test
    void apiCanPlayASequenceOnlyDisabledEmote() {
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.replace(List.of(create("demo:internal", "Internal", false)));
        PlaybackPolicyService policy = policy(
            (ignoredPlayer, ignoredPermission, ignoredDefault) -> {
                throw new AssertionError("API playback must not inspect player permissions");
            },
            new AtomicLong()
        );
        policy.onAccessConfigReload(new AccessConfig(List.of("demo:internal"), List.of()));
        EmotePlayService service = new EmotePlayService(
            catalog,
            policy,
            (ignoredPlayer, ignoredDefinition, ignoredPlacement) -> PlayResultFixture.SUCCESS,
            (ignoredPlayer, ignoredEmote, source) -> {
                assertEquals(PlaySource.API, source);
                return null;
            }
        );

        assertTrue(service.play(null, "demo:internal", PlaySource.API).isSuccess());
    }

    private static PlaybackPolicyService allowedPolicy(AtomicLong tick, EmoteCatalog catalog) {
        return allowedPolicy(catalog, cooldowns(tick));
    }

    private static PlaybackPolicyService allowedPolicy(EmoteCatalog catalog, PlaybackCooldownService cooldowns) {
        PlaybackPolicyService policy = policy(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            catalog,
            cooldowns
        );
        policy.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(new AccessConfig.PermissionEntry(
                "emote.default",
                List.of("demo:wave"),
                Optional.empty(),
                Optional.empty()
            ))
        ));
        return policy;
    }

    private static PlaybackPolicyService policy(
        PlaybackPolicyService.PermissionChecker permissionChecker,
        AtomicLong tick
    ) {
        return policy(permissionChecker, tick, new EmoteCatalog());
    }

    private static PlaybackPolicyService policy(
        PlaybackPolicyService.PermissionChecker permissionChecker,
        AtomicLong tick,
        EmoteCatalog catalog
    ) {
        return policy(permissionChecker, catalog, cooldowns(tick));
    }

    private static PlaybackPolicyService policy(
        PlaybackPolicyService.PermissionChecker permissionChecker,
        EmoteCatalog catalog,
        PlaybackCooldownService cooldowns
    ) {
        PlaybackPolicyService policy = new PlaybackPolicyService(permissionChecker, cooldowns);
        catalog.addListener(policy::onEmoteCatalogChanged);
        return policy;
    }

    private static PlaybackCooldownService cooldowns(AtomicLong tick) {
        return new PlaybackCooldownService(ignoredPlayer -> new UUID(1L, 1L), ignoredPlayer -> tick.get());
    }

    private static EmoteCatalog catalogWithWave(int cooldownTicks) {
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.replace(List.of(create("demo:wave", "Wave", cooldownTicks)));
        return catalog;
    }

    private static void assertHasErrorMessage(PlayResult result) {
        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage().getString().isBlank());
    }
}
