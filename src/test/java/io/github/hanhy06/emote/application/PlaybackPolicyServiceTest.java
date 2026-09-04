package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.permission.PermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.content.PreparedAnimationFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackPolicyServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("4e20ffac-0bc9-4ca1-ad81-d1f1b29e4679");

    @Test
    void commandAppliesStandaloneDisabledAndPermissionPolicies() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, defaultValue) -> permission.equals("emote.default") && defaultValue,
            new AtomicLong()
        );
        loadRules(service, new AccessConfig(
            List.of("demo:disabled"),
            List.of(entry("emote.default", List.of("demo:allowed", "demo:disabled", "demo:internal")))
        ), "demo:allowed", "demo:disabled", "demo:internal", "demo:missing");

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

        PreparedAnimation idle = create("demo:idle", "Idle", 20);
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
        PreparedAnimation emote = create("demo:internal", "Internal", false, 20);

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
        PreparedAnimation emote = create("demo:internal", "Internal", false, 20);

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
        loadRules(service, new AccessConfig(
            List.of(),
            List.of(entry("emote.default", List.of("demo:wave")))
        ), "demo:wave");
        PreparedAnimation emote = create("demo:wave", "Wave", 20);

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
        loadRules(service, new AccessConfig(
            List.of("demo:disabled"),
            List.of(entry("emote.default", List.of("demo:allowed", "demo:disabled", "demo:internal")))
        ), "demo:allowed", "demo:disabled", "demo:internal", "demo:missing");

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

    @Test
    void resolvesRegexRulesAgainstCatalogIdsUsingFullMatches() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals("emote.regex"),
            new AtomicLong()
        );
        loadRules(service, new AccessConfig(
            List.of(),
            List.of(entry("emote.regex", List.of("demo:(wave|dance_[0-9]+)")))
        ), "demo:wave", "demo:dance_12", "demo:wave_fast", "demo:dance_fast");

        assertAllowed(service.evaluate(null, create("demo:wave", "Wave"), PlaySource.COMMAND));
        assertAllowed(service.evaluate(null, create("demo:dance_12", "Dance"), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:wave_fast", "Fast Wave"), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:dance_fast", "Fast Dance"), PlaySource.COMMAND));
    }

    @Test
    void treatsValidEmoteIdsAsLiteralRules() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals("emote.literal"),
            new AtomicLong()
        );
        loadRules(service, new AccessConfig(
            List.of(),
            List.of(entry("emote.literal", List.of("demo:sample.1")))
        ), "demo:sample.1", "demo:samplex1");

        assertAllowed(service.evaluate(null, create("demo:sample.1", "Literal"), PlaySource.COMMAND));
        assertDenied(service.evaluate(null, create("demo:samplex1", "Regex Lookalike"), PlaySource.COMMAND));
    }

    @Test
    void wildcardRuleAppliesWithoutAResolvedCatalogId() {
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals("emote.vip"),
            new AtomicLong()
        );
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(entry("emote.vip", List.of("*")))
        ));

        assertAllowed(service.evaluate(null, create("api:late", "Late API Emote"), PlaySource.COMMAND));
    }

    @Test
    void catalogChangesRebuildTheResolvedPermissionIndex() {
        EmoteCatalog catalog = new EmoteCatalog();
        PlaybackPolicyService service = service(
            (ignoredPlayer, permission, ignoredDefault) -> permission.equals("emote.api"),
            new AtomicLong()
        );
        catalog.addListener(service::onEmoteCatalogChanged);
        service.onAccessConfigReload(new AccessConfig(
            List.of(),
            List.of(entry("emote.api", List.of("api:.*")))
        ));
        PreparedAnimation emote = create("api:wave", "API Wave");

        assertDenied(service.evaluate(null, emote, PlaySource.COMMAND));
        UUID registrationId = catalog.register(emote);
        assertAllowed(service.evaluate(null, emote, PlaySource.COMMAND));
        assertTrue(catalog.unregister(emote.id(), registrationId));
        assertDenied(service.evaluate(null, emote, PlaySource.COMMAND));
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

    private static void loadRules(PlaybackPolicyService service, AccessConfig config, String... emoteIds) {
        service.onAccessConfigReload(config);
        service.onEmoteCatalogChanged(java.util.Arrays.stream(emoteIds).map(id -> create(id, id)).toList());
    }

    private static void assertAllowed(PlaybackPolicyService.Decision decision) {
        assertTrue(decision.isAllowed());
    }

    private static void assertDenied(PlaybackPolicyService.Decision decision) {
        assertFalse(decision.isAllowed());
        assertNotNull(decision.rejection());
    }
}
