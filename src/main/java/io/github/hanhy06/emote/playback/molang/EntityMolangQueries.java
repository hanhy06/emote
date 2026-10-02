package io.github.hanhy06.emote.playback.molang;

import net.minecraft.world.entity.Entity;

public final class EntityMolangQueries {
    private EntityMolangQueries() {}

    public static MolangQuerySource forEntity(Entity entity) {
        return session -> {
            // Keep the head's extra rotation at zero; the root controls the whole model's yaw.
            MolangQuerySource.EMPTY.apply(session);
            MolangQueries.setSpatialQueries(session, entity.position(), entity.getDeltaMovement());
            session.setQuery("is_emoting", 1);
            session.setQuery("is_alive", entity.isAlive() ? 1 : 0);
            session.setQuery("is_on_ground", entity.onGround() ? 1 : 0);
            session.setQuery("is_in_water", entity.isInWater() ? 1 : 0);
            float yaw = entity.getYRot();
            session.setQuery("body_y_rotation", yaw);
            session.setQuery("head_y_rotation", yaw);
            session.setQuery("eye_target_y_rotation", yaw);
        };
    }
}
