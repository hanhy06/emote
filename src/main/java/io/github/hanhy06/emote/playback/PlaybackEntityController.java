package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.emote.EmoteDatapackNames;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

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
        String rootTag = EmoteDatapackNames.rootTag(namespace);
        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof Display.BlockDisplay && entity.entityTags().contains(rootTag)) {
                return entityUuid;
            }
        }
        return null;
    }

    void alignInstanceWithPlayer(ServerPlayer player, UUID rootEntityUuid, Set<UUID> instanceEntityUuids) {
        Entity rootEntity = player.level().getEntity(rootEntityUuid);
        if (rootEntity == null) {
            return;
        }

        float yaw = Mth.wrapDegrees(player.getYRot() + 180.0F);
        rootEntity.snapTo(player.position(), yaw, 0.0F);
        rootEntity.teleportTo(player.getX(), player.getY(), player.getZ());

        for (UUID entityUuid : instanceEntityUuids) {
            Entity entity = player.level().getEntity(entityUuid);
            if (entity != null && entity != rootEntity) {
                entity.snapTo(entity.position(), yaw, 0.0F);
            }
        }
    }

    void copyAnimationTags(ServerLevel sourceLevel, UUID sourceRootEntityUuid, Entity targetRoot) {
        Entity sourceRoot = sourceLevel.getEntity(sourceRootEntityUuid);
        if (sourceRoot == null || targetRoot == null) {
            return;
        }

        for (String tag : sourceRoot.entityTags()) {
            if (EmoteDatapackNames.isAnimationTag(tag)) {
                targetRoot.addTag(tag);
            }
        }
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

    private boolean matchesNamespaceDisplay(Entity entity, String namespace) {
        if (!(entity instanceof Display)) {
            return false;
        }
        return entity.entityTags().stream().anyMatch(tag -> EmoteDatapackNames.isCleanupTag(tag, namespace));
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
