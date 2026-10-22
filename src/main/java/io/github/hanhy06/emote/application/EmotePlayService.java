package io.github.hanhy06.emote.application;

import io.github.hanhy06.emote.api.PlayResult;
import io.github.hanhy06.emote.api.PlaySource;
import io.github.hanhy06.emote.api.PlaybackPlacement;
import io.github.hanhy06.emote.config.AccessConfig;
import io.github.hanhy06.emote.config.AccessConfigListener;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.PreparedEmote;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlayerPlaybackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class EmotePlayService implements AccessConfigListener {
    private static final String DEFAULT_PERMISSION = "emote.default";
    private static final Rules COMMAND_RULES = new Rules(true, true, true, true);
    private static final Rules IDLE_RULES = new Rules(true, true, true, false);
    private static final Rules UNRESTRICTED_RULES = new Rules(false, false, false, false);

    private final EmoteCatalog emoteCatalog;
    private final PlayerPlaybackManager playerPlaybackManager;
    private final ApiEventDispatcher apiEvents;
    private final PlaybackCooldownService cooldowns;

    private List<AccessConfig.PermissionEntry> permissionEntries = List.of();
    private List<AccessConfig.PermissionEntry> wildcardPermissionEntries = List.of();
    private Map<String, List<AccessConfig.PermissionEntry>> permissionEntriesByEmoteId = Map.of();
    private List<String> catalogEmoteIds = List.of();
    private Set<String> disabledEmoteIds = Set.of();

    public EmotePlayService(
        EmoteCatalog emoteCatalog,
        PlaybackCooldownService cooldowns,
        PlayerPlaybackManager playerPlaybackManager,
        ApiEventDispatcher apiEvents
    ) {
        this.emoteCatalog = Objects.requireNonNull(emoteCatalog, "emote catalog");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.playerPlaybackManager = playerPlaybackManager;
        this.apiEvents = apiEvents;
        this.emoteCatalog.addListener(this::onEmoteCatalogChanged);
    }

    public PlayResult play(ServerPlayer player, String emoteId, PlaySource source) {
        return play(player, emoteId, source, PlaybackPlacement.actor());
    }

    public PlayResult play(ServerPlayer player, String emoteId, PlaySource source, PlaybackPlacement placement) {
        PreparedEmote emote = this.emoteCatalog.find(emoteId);
        if (emote == null) {
            return PlayResult.failure("That emote does not exist.");
        }
        Decision decision = evaluate(player, emote, source);
        if (!decision.isAllowed()) {
            return decision.rejection();
        }
        Component cancellationMessage = this.apiEvents.beforePlay(player, emote, source);
        if (cancellationMessage != null) {
            return PlayResult.failure(cancellationMessage);
        }
        claimCooldown(decision);
        PlayResult result;
        try {
            result = this.playerPlaybackManager.start(player, emote, placement);
        } catch (RuntimeException | Error exception) {
            releaseCooldown(decision);
            throw exception;
        }
        if (!result.isSuccess()) {
            releaseCooldown(decision);
        }
        return result;
    }

    @Override
    public void onAccessConfigReload(AccessConfig newConfig) {
        this.permissionEntries = newConfig.permissions();
        this.disabledEmoteIds = Set.copyOf(newConfig.disabled());
        rebuildPermissionIndex();
    }

    private void onEmoteCatalogChanged(List<? extends PreparedEmote> emotes) {
        this.catalogEmoteIds = emotes.stream().map(PreparedEmote::id).toList();
        rebuildPermissionIndex();
    }

    private Decision evaluate(ServerPlayer player, PreparedEmote emote, PlaySource source) {
        Objects.requireNonNull(emote, "emote");
        Objects.requireNonNull(source, "source");

        Rules rules = rulesFor(player, source);
        if (rules.checkStandalone() && !emote.standalone()) {
            return Decision.denied("This emote can only be played as part of a sequence.");
        }
        if (rules.checkDisabled() && this.disabledEmoteIds.contains(emote.id())) {
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
        int baseCooldownTicks = emote.cooldownTicks();
        int cooldownTicks = permission.cooldown()
            .map(modifier -> modifier.apply(baseCooldownTicks))
            .orElse(baseCooldownTicks);
        if (cooldownTicks <= 0) {
            return Decision.allowed();
        }

        PlaybackCooldownService.Status cooldownStatus = this.cooldowns.status(player, emote.id());
        if (cooldownStatus.state() == PlaybackCooldownService.State.IN_USE) {
            return Decision.denied("This emote is already playing.");
        }
        if (cooldownStatus.state() == PlaybackCooldownService.State.COOLING_DOWN) {
            long remainingSeconds = (cooldownStatus.remainingTicks() + 19) / 20;
            return Decision.denied(
                "You can use this emote again in " + remainingSeconds + (remainingSeconds == 1 ? " second." : " seconds.")
            );
        }
        return Decision.allowed(this.cooldowns.reservation(player, emote.id(), cooldownTicks));
    }

    private void claimCooldown(Decision decision) {
        if (decision.cooldownReservation() != null) {
            this.cooldowns.claim(decision.cooldownReservation());
        }
    }

    private void releaseCooldown(Decision decision) {
        if (decision.cooldownReservation() != null) {
            this.cooldowns.release(decision.cooldownReservation());
        }
    }

    public boolean isVisibleForCommand(ServerPlayer player, PreparedEmote emote) {
        Rules rules = rulesFor(player, PlaySource.COMMAND);
        return (!rules.checkStandalone() || emote.standalone())
            && (!rules.checkDisabled() || !this.disabledEmoteIds.contains(emote.id()))
            && (!rules.checkPermission() || resolvePermission(player, emote.id()).allowed());
    }

    public boolean isAvailableForIdle(ServerPlayer player, String emoteId) {
        PreparedEmote emote = this.emoteCatalog.find(emoteId);
        if (emote == null) {
            return false;
        }
        Rules rules = rulesFor(player, PlaySource.IDLE);
        return (!rules.checkStandalone() || emote.standalone())
            && (!rules.checkDisabled() || !this.disabledEmoteIds.contains(emoteId));
    }

    public Optional<AccessConfig.IdleSettings> findIdleSettings(ServerPlayer player) {
        for (AccessConfig.PermissionEntry entry : this.permissionEntries) {
            if (entry.idle().isEmpty()) {
                continue;
            }
            if (PermissionService.has(player, entry.permission(), entry.permission().equals(DEFAULT_PERMISSION))) {
                return entry.idle();
            }
        }
        return Optional.empty();
    }

    private Rules rulesFor(ServerPlayer player, PlaySource source) {
        if (source == PlaySource.API) {
            return UNRESTRICTED_RULES;
        }
        if (PermissionService.has(player, PermissionService.BYPASS_PERMISSION, false)) {
            return UNRESTRICTED_RULES;
        }
        return source == PlaySource.IDLE ? IDLE_RULES : COMMAND_RULES;
    }

    private PermissionResolution resolvePermission(ServerPlayer player, String emoteId) {
        boolean allowed = false;
        for (AccessConfig.PermissionEntry entry : this.permissionEntriesByEmoteId.getOrDefault(emoteId, this.wildcardPermissionEntries)) {
            if (PermissionService.has(player, entry.permission(), entry.permission().equals(DEFAULT_PERMISSION))) {
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
        for (String id : this.catalogEmoteIds) {
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

    private record Decision(
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

    }

    private record Rules(
        boolean checkStandalone,
        boolean checkDisabled,
        boolean checkCooldown,
        boolean checkPermission
    ) {
    }

}
