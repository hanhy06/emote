package io.github.hanhy06.emote.playback;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PlaybackEntityController {
    private static final double NAMESPACE_CLEANUP_SEARCH_DISTANCE = 24.0D;

    Set<UUID> findNamespaceEntityUuids(ServerLevel level, String namespace) {
        Set<UUID> entityUuids = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            if (matchesNamespaceDisplay(entity, namespace)) {
                entityUuids.add(entity.getUUID());
            }
        }
        return entityUuids;
    }

    UUID findRootEntityUuid(ServerLevel level, String namespace, Set<UUID> instanceEntityUuids) {
        String rootTag = namespace + "_root";
        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof Display.BlockDisplay && entity.entityTags().contains(rootTag)) {
                return entityUuid;
            }
        }
        return null;
    }

    void cleanupInstanceEntities(ServerLevel level, Set<UUID> instanceEntityUuids) {
        Map<Integer, Entity> entitiesToKill = new LinkedHashMap<>();
        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = level.getEntity(entityUuid);
            if (entity != null) {
                collectEntityTree(entity, entitiesToKill);
            }
        }
        killEntities(level, entitiesToKill.values());
    }

    void cleanupNamespaceEntitiesNearby(ServerLevel level, String namespace, Vec3 origin) {
        AABB searchBox = new AABB(origin, origin).inflate(NAMESPACE_CLEANUP_SEARCH_DISTANCE);
        List<Display> displaysToKill = level.getEntitiesOfClass(
                Display.class,
                searchBox,
                entity -> matchesNamespaceDisplay(entity, namespace)
        );
        killEntities(level, displaysToKill);
    }

    void cleanupNamespaceEntities(ServerLevel level, String namespace) {
        Map<Integer, Entity> entitiesToKill = new LinkedHashMap<>();
        for (Entity entity : level.getAllEntities()) {
            if (matchesNamespaceDisplay(entity, namespace)) {
                collectEntityTree(entity, entitiesToKill);
            }
        }
        killEntities(level, entitiesToKill.values());
    }

    static boolean isCleanupTag(String tag, String namespace) {
        if (tag.equals(namespace) || tag.equals(namespace + "_root") || tag.equals(namespace + "_camera")) {
            return true;
        }
        if (!tag.startsWith(namespace + "_")) {
            return false;
        }

        String suffix = tag.substring(namespace.length() + 1);
        if (suffix.isEmpty()) {
            return false;
        }
        if (suffix.charAt(0) == 'p') {
            return suffix.length() > 1 && suffix.substring(1).chars().allMatch(Character::isDigit);
        }
        return suffix.chars().allMatch(Character::isDigit);
    }

    private boolean matchesNamespaceDisplay(Entity entity, String namespace) {
        if (!(entity instanceof Display)) {
            return false;
        }
        return entity.entityTags().stream().anyMatch(tag -> isCleanupTag(tag, namespace));
    }

    private void collectEntityTree(Entity entity, Map<Integer, Entity> entitiesToKill) {
        if (entitiesToKill.containsKey(entity.getId())) {
            return;
        }
        for (Entity passenger : entity.getPassengers()) {
            collectEntityTree(passenger, entitiesToKill);
        }
        entitiesToKill.put(entity.getId(), entity);
    }

    private void killEntities(ServerLevel level, Iterable<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (!entity.isRemoved()) {
                entity.kill(level);
            }
        }
    }
}
