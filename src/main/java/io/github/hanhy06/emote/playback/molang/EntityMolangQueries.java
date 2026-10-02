package io.github.hanhy06.emote.playback.molang;

import net.minecraft.world.entity.Entity;

public final class EntityMolangQueries {
    private EntityMolangQueries() {}

    public static MolangQuerySource forEntity(Entity entity) {
        return session -> {
            MolangQuerySource.EMPTY.apply(session);
            MolangQueries.setSpatialQueries(session, entity.position(), entity.getDeltaMovement());
            session.setQuery("is_emoting", 1);
            session.setQuery("is_alive", entity.isAlive() ? 1 : 0);
            session.setQuery("is_on_ground", entity.onGround() ? 1 : 0);
            session.setQuery("is_in_water", entity.isInWater() ? 1 : 0);
            session.setQuery("target_x_rotation", entity.getXRot());
            session.setQuery("target_y_rotation", entity.getYRot());
            session.setQuery("body_x_rotation", entity.getXRot());
            session.setQuery("body_y_rotation", entity.getYRot());
            session.setQuery("head_x_rotation", entity.getXRot());
            session.setQuery("head_y_rotation", entity.getYRot());
        };
    }
}
