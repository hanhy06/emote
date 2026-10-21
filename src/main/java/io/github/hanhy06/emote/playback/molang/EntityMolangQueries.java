package io.github.hanhy06.emote.playback.molang;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class EntityMolangQueries {
    private EntityMolangQueries() {}

    public static MolangQuerySource forEntity(Entity entity) {
        return (session, queries) -> {
            // Keep the head's extra rotation at zero; the root controls the whole model's yaw.
            MolangQuerySource.EMPTY.apply(session, queries);
            MolangQueries.setSpatialQueries(session, queries.contains("position") ? entity.position() : Vec3.ZERO,
                queries.contains("position_delta") || queries.contains("movement_direction") ? entity.getDeltaMovement() : Vec3.ZERO, queries);
            if (queries.contains("is_emoting")) session.setQuery("is_emoting", 1);
            if (queries.contains("is_alive")) session.setQuery("is_alive", entity.isAlive() ? 1 : 0);
            if (queries.contains("is_on_ground")) session.setQuery("is_on_ground", entity.onGround() ? 1 : 0);
            if (queries.contains("is_in_water")) session.setQuery("is_in_water", entity.isInWater() ? 1 : 0);
            if (queries.contains("body_y_rotation") || queries.contains("head_y_rotation") || queries.contains("eye_target_y_rotation")) {
                float yaw = entity.getYRot();
                if (queries.contains("body_y_rotation")) session.setQuery("body_y_rotation", yaw);
                if (queries.contains("head_y_rotation")) session.setQuery("head_y_rotation", yaw);
                if (queries.contains("eye_target_y_rotation")) session.setQuery("eye_target_y_rotation", yaw);
            }
        };
    }
}
