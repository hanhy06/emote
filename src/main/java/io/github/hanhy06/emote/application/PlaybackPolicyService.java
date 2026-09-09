package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class PlaybackPolicyService implements AccessConfigListener {
    private static final String DEFAULT_PERMISSION = "emote.default";
    private static final Rules COMMAND_RULES = new Rules(true, true, true, true);
    private static final Rules IDLE_RULES = new Rules(true, true, true, false);
    private static final Rules UNRESTRICTED_RULES = new Rules(false, false, false, false);

    private final PermissionChecker permissionChecker;
    private final PlaybackCooldownService cooldowns;

    private List<AccessConfig.PermissionEntry> permissionEntries = List.of();
    private List<AccessConfig.PermissionEntry> wildcardPermissionEntries = List.of();
    private Map<String, List<AccessConfig.PermissionEntry>> permissionEntriesByEmoteId = Map.of();
    private List<String> emoteIds = List.of();
    private Set<String> disabled = Set.of();

    public PlaybackPolicyService(
        PermissionService permissionService,
        EmoteCatalog emoteCatalog,
        PlaybackCooldownService cooldowns
    ) {
        this(permissionService::has, cooldowns);
        Objects.requireNonNull(emoteCatalog, "emote catalog").addListener(this::onEmoteCatalogChanged);
    }

    PlaybackPolicyService(
        PermissionChecker permissionChecker,
        PlaybackCooldownService cooldowns
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permission checker");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
    }

    @Override
    public void onAccessConfigReload(AccessConfig newConfig) {
        this.permissionEntries = newConfig.permissions();
        this.disabled = Set.copyOf(newConfig.disabled());
        rebuildPermissionIndex();
    }

    void onEmoteCatalogChanged(List<? extends PlayableEmote> emotes) {
        this.emoteIds = emotes.stream().map(PlayableEmote::id).toList();
        rebuildPermissionIndex();
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
        PermissionResolution permission = rules.checkPermission() ? resolvePermission(player, emote.id()) : null;
        if (permission != null && !permission.allowed()) {
            return Decision.denied("You do not have permission to use this emote.");
        }
        if (!rules.checkCooldown() || emote.cooldownTicks() <= 0) {
            return Decision.allowed();
        }

        if (permission == null) {
            permission = resolvePermission(player, emote.id());
        }
        int cooldownTicks = permission.cooldown()
            .map(modifier -> modifier.apply(emote.cooldownTicks()))
            .orElse(emote.cooldownTicks());
        if (cooldownTicks <= 0) {
            return Decision.allowed();
        }

        PlaybackCooldownService.Status cooldownStatus = this.cooldowns.status(player, emote.id());
        if (cooldownStatus.state() == PlaybackCooldownService.State.IN_USE) {
            return Decision.denied("This emote is already playing.");
        }
        if (cooldownStatus.state() == PlaybackCooldownService.State.COOLING_DOWN) {
            long remainingTicks = cooldownStatus.remainingTicks();
            long remainingSeconds = (remainingTicks + 19) / 20;
            return Decision.denied(
                "You can use this emote again in " + remainingSeconds + (remainingSeconds == 1 ? " second." : " seconds.")
            );
        }
        return Decision.allowed(this.cooldowns.reservation(player, emote.id(), cooldownTicks));
    }

    void claimCooldown(Decision decision) {
        if (decision.cooldownReservation() != null) {
            this.cooldowns.claim(decision.cooldownReservation());
        }
    }

    void releaseCooldown(Decision decision) {
        if (decision.cooldownReservation() != null) {
            this.cooldowns.release(decision.cooldownReservation());
        }
    }

    public boolean isVisibleForCommand(ServerPlayer player, PlayableEmote emote) {
        Rules rules = rulesFor(player, PlaySource.COMMAND);
        return (!rules.checkStandalone() || emote.standalone())
            && (!rules.checkDisabled() || !this.disabled.contains(emote.id()))
            && (!rules.checkPermission() || resolvePermission(player, emote.id()).allowed());
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

    private Rules rulesFor(ServerPlayer player, PlaySource source) {
        if (source == PlaySource.API) {
            return UNRESTRICTED_RULES;
        }
        if (this.permissionChecker.test(player, PermissionService.BYPASS_PERMISSION, false)) {
            return UNRESTRICTED_RULES;
        }
        return source == PlaySource.IDLE ? IDLE_RULES : COMMAND_RULES;
    }

    private PermissionResolution resolvePermission(ServerPlayer player, String id) {
        boolean allowed = false;
        for (AccessConfig.PermissionEntry entry : this.permissionEntriesByEmoteId.getOrDefault(id, this.wildcardPermissionEntries)) {
            boolean grantedByDefault = entry.permission().equals(DEFAULT_PERMISSION);
            if (this.permissionChecker.test(player, entry.permission(), grantedByDefault)) {
                allowed = true;
                if (entry.cooldown().isPresent()) {
                    return new PermissionResolution(true, entry.cooldown());
                }
            }
        }
        return new PermissionResolution(allowed, Optional.empty());
    }

    private void rebuildPermissionIndex() {
        this.wildcardPermissionEntries = this.permissionEntries.stream()
            .filter(AccessConfig.PermissionEntry::appliesToAllEmotes)
            .toList();

        Map<String, List<AccessConfig.PermissionEntry>> entriesById = new HashMap<>();
        for (String id : this.emoteIds) {
            List<AccessConfig.PermissionEntry> matches = this.permissionEntries.stream()
                .filter(entry -> entry.appliesToAllEmotes() || entry.matchesEmote(id))
                .toList();
            if (!matches.isEmpty()) {
                entriesById.put(id, matches);
            }
        }
        this.permissionEntriesByEmoteId = Map.copyOf(entriesById);
    }

    private record PermissionResolution(boolean allowed, Optional<AccessConfig.CooldownModifier> cooldown) {
    }

    record Decision(
        PlayResult rejection,
        PlaybackCooldownService.Reservation cooldownReservation
    ) {
        private static Decision allowed() {
            return new Decision(null, null);
        }

        private static Decision allowed(PlaybackCooldownService.Reservation cooldownReservation) {
            return new Decision(null, cooldownReservation);
        }

        private static Decision denied(String message) {
            return new Decision(PlayResult.failure(message), null);
        }

        boolean isAllowed() {
            return this.rejection == null;
        }

        int cooldownTicks() {
            return this.cooldownReservation == null ? 0 : this.cooldownReservation.durationTicks();
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
