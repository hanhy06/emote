package io.github.hanhy06.emote.skin.mineskin;

import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;

import java.util.*;

final class MineSkinBakeTask {
    private final String key;
    private final PlayerSkinSource source;
    private final long queueGeneration;
    private final Set<UUID> subscribers = new LinkedHashSet<>();
    private final Set<PlayerSkinRegion> requiredRegions = new LinkedHashSet<>();
    private final Map<PlayerSkinRegion, Integer> retryAttempts = new HashMap<>();
    private final Map<PlayerSkinRegion, Long> retryTimes = new HashMap<>();

    private Stage stage = Stage.QUEUED;
    private long failedAtEpochMillis;

    MineSkinBakeTask(PlayerSkinSource source, long queueGeneration) {
        this.source = source;
        this.key = source.textureHash() + ":" + (source.slimModel() ? "slim" : "classic");
        this.queueGeneration = queueGeneration;
    }

    String key() {
        return this.key;
    }

    PlayerSkinSource source() {
        return this.source;
    }

    long queueGeneration() {
        return this.queueGeneration;
    }

    synchronized void addSubscriber(UUID playerUuid) {
        this.subscribers.add(playerUuid);
    }

    synchronized boolean addRequiredRegions(Set<PlayerSkinRegion> regions) {
        boolean changed = this.requiredRegions.addAll(regions);
        if (changed && (this.stage == Stage.COMPLETE || this.stage == Stage.FAILED)) {
            this.stage = Stage.QUEUED;
        }
        return changed;
    }

    synchronized Set<PlayerSkinRegion> requiredRegions() {
        return Set.copyOf(this.requiredRegions);
    }

    synchronized Set<UUID> subscribers() {
        return Set.copyOf(this.subscribers);
    }

    synchronized Stage stage() {
        return this.stage;
    }

    synchronized void queue() {
        this.stage = Stage.QUEUED;
    }

    synchronized boolean canRestart(long nowEpochMillis, long retryDelayMillis) {
        return this.stage == Stage.FAILED && nowEpochMillis - this.failedAtEpochMillis >= retryDelayMillis;
    }

    synchronized long failedAtEpochMillis() {
        return this.failedAtEpochMillis;
    }

    synchronized void updateStage(Stage stage) {
        if (this.stage != Stage.CANCELLED) {
            this.stage = stage;
        }
    }

    synchronized void markCompleted(PlayerSkinRegion region) {
        this.retryAttempts.remove(region);
        this.retryTimes.remove(region);
    }

    synchronized int recordRetry(PlayerSkinRegion region, long retryAtEpochMillis) {
        Long previousRetryAt = this.retryTimes.put(region, retryAtEpochMillis);
        if (previousRetryAt != null && previousRetryAt == retryAtEpochMillis) {
            return this.retryAttempts.getOrDefault(region, 1);
        }
        return this.retryAttempts.merge(region, 1, Integer::sum);
    }

    synchronized void waitForRetry() {
        if (this.stage != Stage.CANCELLED) {
            this.stage = Stage.RETRY_WAIT;
        }
    }

    synchronized boolean isSatisfiedBy(Set<PlayerSkinRegion> availableRegions) {
        return availableRegions.containsAll(this.requiredRegions);
    }

    synchronized boolean complete() {
        if (this.stage == Stage.CANCELLED) {
            return false;
        }
        boolean changed = this.stage != Stage.COMPLETE;
        this.stage = Stage.COMPLETE;
        return changed;
    }

    synchronized boolean fail() {
        if (this.stage == Stage.CANCELLED) {
            return false;
        }
        boolean changed = this.stage != Stage.FAILED;
        this.stage = Stage.FAILED;
        this.failedAtEpochMillis = System.currentTimeMillis();
        return changed;
    }

    synchronized void cancel() {
        this.stage = Stage.CANCELLED;
    }

    enum Stage {
        QUEUED,
        DOWNLOADING_SKIN,
        BAKING_PART,
        WAITING_FOR_MINESKIN,
        RETRY_WAIT,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
