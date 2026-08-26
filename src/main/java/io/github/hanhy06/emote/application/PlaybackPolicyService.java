package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public final class PlaybackPolicyService implements AccessConfigListener {
    private static final String DEFAULT_PERMISSION = "emote.default";
    private static final String ALL_IDS = "*";
    private static final Rules COMMAND_RULES = new Rules(true, true, true, true);
    private static final Rules IDLE_RULES = new Rules(true, true, true, false);
    private static final Rules UNRESTRICTED_RULES = new Rules(false, false, false, false);

    private final PermissionChecker permissionChecker;
    private final Function<ServerPlayer, UUID> playerIdResolver;
    private final ToLongFunction<ServerPlayer> tickSource;
    private final Cooldowns cooldowns = new Cooldowns();

    private List<AccessConfig.PermissionEntry> permissionEntries = List.of();
    private Set<String> disabled = Set.of();

    public PlaybackPolicyService(PermissionService permissionService) {
        this(permissionService::has, ServerPlayer::getUUID, player -> player.level().getGameTime());
    }

    PlaybackPolicyService(
        PermissionChecker permissionChecker,
        Function<ServerPlayer, UUID> playerIdResolver,
        ToLongFunction<ServerPlayer> tickSource
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
        this.playerIdResolver = Objects.requireNonNull(playerIdResolver, "player id resolver");
        this.tickSource = Objects.requireNonNull(tickSource, "tick source");
    }

    @Override
    public void onAccessConfigReload(AccessConfig newConfig) {
        this.permissionEntries = newConfig.permissions();
        this.disabled = Set.copyOf(newConfig.disabled());
    }

    Decision evaluate(ServerPlayer player, PlayableEmote emote, PlaySource source) {
        Objects.requireNonNull(emote, "emote");
        Objects.requireNonNull(source, "source");

        Rules rules = rulesFor(player, source);
        if (rules.checkStandalone() && !emote.standalone()) {
            return Decision.denied("This emote can only be played as part of a sequence.");
        }
        if (rules.checkDisabled() && this.disabled.contains(emote.id())) {
            return Decision.denied("This emote is currently unavailable.");
        }
        if (rules.checkPermission() && !hasEmotePermission(player, emote.id())) {
            return Decision.denied("You do not have permission to use this emote.");
        }
        if (!rules.checkCooldown() || emote.cooldownTicks() <= 0) {
            return Decision.allowed();
        }

        UUID playerId = this.playerIdResolver.apply(player);
        long currentTick = this.tickSource.applyAsLong(player);
        long remainingTicks = this.cooldowns.remainingTicks(playerId, emote.id(), currentTick);
        if (remainingTicks > 0L) {
            long remainingSeconds = (remainingTicks + 19) / 20;
            return Decision.denied(
                "You can use this emote again in " + remainingSeconds + (remainingSeconds == 1 ? " second." : " seconds.")
            );
        }
        return Decision.allowed(playerId, emote.id(), currentTick, emote.cooldownTicks());
    }

    void onPlaybackStarted(Decision decision) {
        if (decision.cooldownTicks() > 0) {
            this.cooldowns.start(
                decision.playerId(),
                decision.emoteId(),
                decision.currentTick(),
                decision.cooldownTicks()
            );
        }
    }

    public boolean isVisibleForCommand(ServerPlayer player, PlayableEmote emote) {
        Rules rules = rulesFor(player, PlaySource.COMMAND);
        return (!rules.checkStandalone() || emote.standalone())
            && (!rules.checkDisabled() || !this.disabled.contains(emote.id()))
            && (!rules.checkPermission() || hasEmotePermission(player, emote.id()));
    }

    public Optional<AccessConfig.IdleSettings> findIdleSettings(ServerPlayer player) {
        for (AccessConfig.PermissionEntry entry : this.permissionEntries) {
            if (entry.idle().isEmpty()) {
                continue;
            }
            boolean grantedByDefault = entry.permission().equals(DEFAULT_PERMISSION);
            if (this.permissionChecker.test(player, entry.permission(), grantedByDefault)) {
                return entry.idle();
            }
        }
        return Optional.empty();
    }

    public void clearCooldowns() {
        this.cooldowns.clear();
    }

    private Rules rulesFor(ServerPlayer player, PlaySource source) {
        if (source == PlaySource.API) {
            return UNRESTRICTED_RULES;
        }
        if (this.permissionChecker.test(player, PermissionService.BYPASS_PERMISSION, false)) {
            return UNRESTRICTED_RULES;
        }
        return source == PlaySource.IDLE ? IDLE_RULES : COMMAND_RULES;
    }

    private boolean hasEmotePermission(ServerPlayer player, String id) {
        for (AccessConfig.PermissionEntry entry : this.permissionEntries) {
            List<String> ids = entry.emotes();
            if (!ids.contains(ALL_IDS) && !ids.contains(id)) {
                continue;
            }
            boolean grantedByDefault = entry.permission().equals(DEFAULT_PERMISSION);
            if (this.permissionChecker.test(player, entry.permission(), grantedByDefault)) {
                return true;
            }
        }
        return false;
    }

    private static final class Cooldowns {
        private final Map<UUID, Map<String, Long>> readyTicks = new HashMap<>();

        private long remainingTicks(UUID playerId, String emoteId, long currentTick) {
            Map<String, Long> playerCooldowns = this.readyTicks.get(playerId);
            if (playerCooldowns == null) {
                return 0L;
            }
            long remaining = playerCooldowns.getOrDefault(emoteId, currentTick) - currentTick;
            if (remaining <= 0L) {
                playerCooldowns.remove(emoteId);
                if (playerCooldowns.isEmpty()) {
                    this.readyTicks.remove(playerId);
                }
                return 0L;
            }
            return remaining;
        }

        private void start(UUID playerId, String emoteId, long currentTick, int cooldownTicks) {
            if (cooldownTicks > 0) {
                this.readyTicks.computeIfAbsent(playerId, ignored -> new HashMap<>())
                    .put(emoteId, currentTick + cooldownTicks);
            }
        }

        private void clear() {
            this.readyTicks.clear();
        }
    }

    record Decision(
        PlayResult rejection,
        UUID playerId,
        String emoteId,
        long currentTick,
        int cooldownTicks
    ) {
        private static Decision allowed() {
            return new Decision(null, null, null, 0L, 0);
        }

        private static Decision allowed(UUID playerId, String emoteId, long currentTick, int cooldownTicks) {
            return new Decision(null, playerId, emoteId, currentTick, cooldownTicks);
        }

        private static Decision denied(String message) {
            return new Decision(PlayResult.failure(message), null, null, 0L, 0);
        }

        boolean isAllowed() {
            return this.rejection == null;
        }
    }

    private record Rules(
        boolean checkStandalone,
        boolean checkDisabled,
        boolean checkCooldown,
        boolean checkPermission
    ) {
    }

    @FunctionalInterface
    interface PermissionChecker {
        boolean test(ServerPlayer player, String permission, boolean defaultValue);
    }
}
