package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedDefinition;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.hanhy06.emote.content.PreparedEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class EmotePlayServiceTest {
    @Test
    void enforcesCooldownAfterSuccessfulPlayback() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("minecraft:wave", "Wave", 20)), List.of());
        AtomicLong tick = new AtomicLong();
        EmotePlayService service = new EmotePlayService(
            registry,
            (ignoredPlayer, ignoredDefinition) -> true,
            ignoredDefinition -> false,
            ignoredPlayer -> false,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null,
            ignoredPlayer -> new UUID(1L, 1L),
            ignoredPlayer -> tick.get()
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        assertHasErrorMessage(service.play(null, "minecraft:wave"));
        tick.set(20L);
        assertTrue(service.play(null, "minecraft:wave").isSuccess());
    }

    @Test
    void clearsCooldownsBetweenServerInstances() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("minecraft:wave", "Wave", 20)), List.of());
        AtomicLong tick = new AtomicLong(10_000L);
        EmotePlayService service = new EmotePlayService(
            registry,
            (ignoredPlayer, ignoredDefinition) -> true,
            ignoredDefinition -> false,
            ignoredPlayer -> false,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null,
            ignoredPlayer -> new UUID(1L, 1L),
            ignoredPlayer -> tick.get()
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        tick.set(0L);
        assertFalse(service.play(null, "minecraft:wave").isSuccess());

        service.clearCooldowns();

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
    }

    @Test
    void bypassIgnoresAnActiveCooldown() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("minecraft:wave", "Wave", 20)), List.of());
        AtomicBoolean bypass = new AtomicBoolean();
        EmotePlayService service = new EmotePlayService(
            registry,
            (ignoredPlayer, ignoredDefinition) -> true,
            ignoredDefinition -> false,
            ignoredPlayer -> bypass.get(),
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null,
            ignoredPlayer -> new UUID(1L, 1L),
            ignoredPlayer -> 0L
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        bypass.set(true);
        assertTrue(service.play(null, "minecraft:wave").isSuccess());
    }

    @Test
    void playReturnsSuccess() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertTrue(result.isSuccess());
    }

    @Test
    void playReturnsPlaybackFailure() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.failure(" Animation unavailable. "),
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        PlayResult result = service.play(null, "minecraft:wave");

        assertHasErrorMessage(result);
    }

    @Test
    void selectionRequiresTheExactId() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertTrue(service.play(null, "minecraft:wave").isSuccess());
        assertHasErrorMessage(service.play(null, "wave"));
    }

    @Test
    void rejectsBlockedEmote() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> false,
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertHasErrorMessage(service.play(null, "minecraft:wave"));
    }

    @Test
    void idlePlaybackDoesNotRequireEmotePermission() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> fail("Idle playback must not check emote permission"),
            (ignoredPlayer, ignoredDefinition) -> PlayResult.SUCCESS,
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertTrue(service.play(null, "minecraft:wave", PlaySource.IDLE).isSuccess());
    }

    @Test
    void idlePlaybackRejectsDisabledEmote() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> fail("Idle playback must not check emote permission"),
            ignoredDefinition -> true,
            ignoredPlayer -> false,
            (ignoredPlayer, ignoredDefinition) -> fail("Disabled idle emote must not start"),
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null,
            ignoredPlayer -> new UUID(0L, 0L),
            ignoredPlayer -> 0L
        );

        assertHasErrorMessage(service.play(null, "minecraft:wave", PlaySource.IDLE));
    }

    @Test
    void apiPlaybackStillRequiresEmotePermission() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> false,
            (ignoredPlayer, ignoredDefinition) -> fail("Blocked API playback must not start"),
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertHasErrorMessage(service.play(null, "minecraft:wave", PlaySource.API));
    }

    @Test
    void rejectsDirectPlaybackOfSequenceOnlyAnimation() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("minecraft:sit_idle", "Sit Idle", false)), List.of());
        EmotePlayService service = new EmotePlayService(
            registry,
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> fail("Sequence-only animation must not start directly"),
            (ignoredPlayer, ignoredEmote, ignoredSource) -> null
        );

        assertHasErrorMessage(service.play(null, "minecraft:sit_idle"));
        assertHasErrorMessage(service.play(null, "minecraft:sit_idle", PlaySource.IDLE));
    }

    @Test
    void listenerCanCancelPlaybackWithAComponentMessage() {
        EmotePlayService service = new EmotePlayService(
            createRegistry(),
            (ignoredPlayer, ignoredDefinition) -> true,
            (ignoredPlayer, ignoredDefinition) -> fail("Cancelled playback must not start"),
            (ignoredPlayer, ignoredEmote, ignoredSource) ->
                Component.literal("Playback blocked by another mod.")
        );

        PlayResult result = service.play(null, "minecraft:wave", PlaySource.API);

        assertHasErrorMessage(result);
    }

    private static void assertHasErrorMessage(PlayResult result) {
        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage().getString().isBlank());
    }

    private EmoteCatalog createRegistry() {
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("wave", "Wave")), List.of());
        return registry;
    }
}
