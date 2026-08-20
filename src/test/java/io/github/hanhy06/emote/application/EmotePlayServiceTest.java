package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.content.EmoteCatalog;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.content.PreparedEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotePlayServiceTest {
    @Test
    void rejectsUnknownIdsBeforeEvaluatingPlaybackPolicy() {
        EmotePlayService service = new EmotePlayService(
            new EmoteCatalog(),
            policy((ignoredPlayer, ignoredPermission, ignoredDefault) -> {
                throw new AssertionError("Unknown IDs must not reach playback policy");
            }, new AtomicLong()),
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
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
            (ignoredPlayer, ignoredDefinition) -> {
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
        PlaybackPolicyService policy = allowedPolicy(tick);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalogWithWave(20),
            policy,
            (ignoredPlayer, ignoredDefinition) -> {
                starts.incrementAndGet();
                return PlayResult.SUCCESS;
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
        PlaybackPolicyService policy = allowedPolicy(tick);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalogWithWave(20),
            policy,
            (ignoredPlayer, ignoredDefinition) -> {
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
    void successfulPlaybackStartsCooldown() {
        AtomicLong tick = new AtomicLong();
        PlaybackPolicyService policy = allowedPolicy(tick);
        AtomicInteger starts = new AtomicInteger();
        EmotePlayService service = new EmotePlayService(
            catalogWithWave(20),
            policy,
            (ignoredPlayer, ignoredDefinition) -> {
                starts.incrementAndGet();
                return PlayResult.SUCCESS;
            },
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertTrue(service.play(null, "demo:wave").isSuccess());
        assertHasErrorMessage(service.play(null, "demo:wave"));
        assertEquals(1, starts.get());
    }

    @Test
    void apiCanPlayASequenceOnlyDisabledEmote() {
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.replace(List.of(create("demo:internal", "Internal", false)), List.of());
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
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, source) -> {
                assertEquals(PlaySource.API, source);
                return null;
            }
        );

        assertTrue(service.play(null, "demo:internal", PlaySource.API).isSuccess());
    }

    private static PlaybackPolicyService allowedPolicy(AtomicLong tick) {
        PlaybackPolicyService policy = policy(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            tick
        );
        policy.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(new AccessConfig.PermissionEntry("emote.default", List.of("demo:wave"), Optional.empty()))
        ));
        return policy;
    }

    private static PlaybackPolicyService policy(
        PlaybackPolicyService.PermissionChecker permissionChecker,
        AtomicLong tick
    ) {
        return new PlaybackPolicyService(
            permissionChecker,
            ignoredPlayer -> new UUID(1L, 1L),
            ignoredPlayer -> tick.get()
        );
    }

    private static EmoteCatalog catalogWithWave(int cooldownTicks) {
        EmoteCatalog catalog = new EmoteCatalog();
        catalog.replace(List.of(create("demo:wave", "Wave", cooldownTicks)), List.of());
        return catalog;
    }

    private static void assertHasErrorMessage(PlayResult result) {
        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage().getString().isBlank());
    }
}
