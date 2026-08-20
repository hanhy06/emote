package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.content.PreparedEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackPolicyServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("4e20ffac-0bc9-4ca1-ad81-d1f1b29e4679");

    @Test
    void commandAppliesStandaloneDisabledAndPermissionPolicies() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            new AtomicLong()
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of("demo:disabled"),
            List.of(entry("emote.default", List.of("demo:allowed", "demo:disabled", "demo:internal")))
        ));

        assertAllowed(service.evaluate(null, create("demo:allowed", "Allowed"), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:disabled", "Disabled"), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:internal", "Internal", false), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:missing", "Missing"), PlaySource.COMMAND));
    }

    @Test
    void idleSkipsOnlyTheEmotePermissionPolicy() {
        AtomicLong tick = new AtomicLong();
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> {
                assertEquals(PermissionService.BYPASS_PERMISSION, permission);
                return false;
            },
            tick
        );
        service.onAccessConfigReload(new AccessConfig(List.of("demo:disabled"), List.of()));

        PreparedEmote idle = create("demo:idle", "Idle", 20);
        PlaybackPolicyService.Decision first = service.evaluate(null, idle, PlaySource.IDLE);
        assertAllowed(first);
        service.onPlaybackStarted(first);

        assertDenied(service.evaluate(null, idle, PlaySource.IDLE));
        assertDenied(service.evaluate(null, create("demo:disabled", "Disabled"), PlaySource.IDLE));
        assertDenied(service.evaluate(null, create("demo:internal", "Internal", false), PlaySource.IDLE));
    }

    @Test
    void apiSkipsOptionalPoliciesWithoutCheckingPlayerPermissions() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, ignoredPermission, ignoredDefault) -> fail("API must not check player permissions"),
            new AtomicLong()
        );
        service.onAccessConfigReload(new AccessConfig(List.of("demo:internal"), List.of()));
        PreparedEmote emote = create("demo:internal", "Internal", false, 20);

        PlaybackPolicyService.Decision first = service.evaluate(null, emote, PlaySource.API);
        assertAllowed(first);
        service.onPlaybackStarted(first);

        assertAllowed(service.evaluate(null, emote, PlaySource.API));
    }

    @Test
    void bypassSkipsStandaloneDisabledPermissionAndCooldownPolicies() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals(PermissionService.BYPASS_PERMISSION),
            new AtomicLong()
        );
        service.onAccessConfigReload(new AccessConfig(List.of("demo:internal"), List.of()));
        PreparedEmote emote = create("demo:internal", "Internal", false, 20);

        PlaybackPolicyService.Decision first = service.evaluate(null, emote, PlaySource.COMMAND);
        assertAllowed(first);
        service.onPlaybackStarted(first);

        assertAllowed(service.evaluate(null, emote, PlaySource.COMMAND));
        assertTrue(service.isVisibleForCommand(null, emote));
    }

    @Test
    void commandCooldownStartsOnlyAfterSuccessfulPlaybackIsReported() {
        AtomicLong tick = new AtomicLong();
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            tick
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(entry("emote.default", List.of("demo:wave")))
        ));
        PreparedEmote emote = create("demo:wave", "Wave", 20);

        PlaybackPolicyService.Decision notStarted = service.evaluate(null, emote, PlaySource.COMMAND);
        assertAllowed(notStarted);
        assertAllowed(service.evaluate(null, emote, PlaySource.COMMAND));

        service.onPlaybackStarted(notStarted);
        assertDenied(service.evaluate(null, emote, PlaySource.COMMAND));
        service.clearCooldowns();
        assertAllowed(service.evaluate(null, emote, PlaySource.COMMAND));

        service.onPlaybackStarted(notStarted);
        tick.set(20L);
        assertAllowed(service.evaluate(null, emote, PlaySource.COMMAND));
    }

    @Test
    void commandVisibilityUsesStaticCommandPoliciesButNotCooldown() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            new AtomicLong()
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of("demo:disabled"),
            List.of(entry("emote.default", List.of("demo:allowed", "demo:disabled", "demo:internal")))
        ));

        assertTrue(service.isVisibleForCommand(null, create("demo:allowed", "Allowed", 20)));
        assertFalse(service.isVisibleForCommand(null, create("demo:disabled", "Disabled")));
        assertFalse(service.isVisibleForCommand(null, create("demo:internal", "Internal", false)));
        assertFalse(service.isVisibleForCommand(null, create("demo:missing", "Missing")));
    }

    @Test
    void findsFirstIdleSettingsForAnAllowedPermissionInConfigOrder() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals("emote.vip") || permission.equals("emote.default"),
            new AtomicLong()
        );
        AccessConfig.IdleSettings vipIdle = new AccessConfig.IdleSettings(200, List.of("demo:vip-idle"));
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(
                new AccessConfig.PermissionEntry("emote.vip", List.of(), Optional.of(vipIdle)),
                new AccessConfig.PermissionEntry(
                    "emote.default",
                    List.of(),
                    Optional.of(new AccessConfig.IdleSettings(400, List.of("demo:idle")))
                )
            )
        ));

        assertEquals(vipIdle, service.findIdleSettings(null).orElseThrow());
    }

    private static PlaybackPolicyService service(
        PlaybackPolicyService.PermissionChecker permissionChecker,
        AtomicLong tick
    ) {
        return new PlaybackPolicyService(permissionChecker, ignoredPlayer -> PLAYER_ID, ignoredPlayer -> tick.get());
    }

    private static AccessConfig.PermissionEntry entry(String permission, List<String> emotes) {
        return new AccessConfig.PermissionEntry(permission, emotes, Optional.empty());
    }

    private static void assertAllowed(PlaybackPolicyService.Decision decision) {
        assertTrue(decision.isAllowed());
    }

    private static void assertDenied(PlaybackPolicyService.Decision decision) {
        assertFalse(decision.isAllowed());
        assertNotNull(decision.rejection());
    }
}
