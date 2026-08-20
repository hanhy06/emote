package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.content.PreparedDefinition;
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
    private final EmoteCooldowns cooldowns = new EmoteCooldowns();

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

    Decision evaluate(ServerPlayer player, PreparedDefinition emote, PlaySource source) {
        Objects.requireNonNull(emote, "emote");
        Objects.requireNonNull(source, "source");

        Rules rules = rulesFor(player, source);
        if (rules.checkStandalone() && !emote.standalone()) {
            return Decision.denied("Sequence-only animation: " + emote.id());
        }
        if (rules.checkDisabled() && this.disabled.contains(emote.id())) {
            return Decision.denied("Disabled emote: " + emote.id());
        }
        if (rules.checkPermission() && !hasEmotePermission(player, emote.id())) {
            return Decision.denied("No emote permission.");
        }
        if (!rules.checkCooldown() || emote.cooldownTicks() <= 0) {
            return Decision.allowed();
        }

        UUID playerId = this.playerIdResolver.apply(player);
        long currentTick = this.tickSource.applyAsLong(player);
        long remainingTicks = this.cooldowns.remainingTicks(playerId, emote.id(), currentTick);
        if (remainingTicks > 0L) {
            return Decision.denied("Emote cooldown: " + (remainingTicks + 19) / 20 + "s remaining.");
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

    public boolean isVisibleForCommand(ServerPlayer player, PreparedDefinition emote) {
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
