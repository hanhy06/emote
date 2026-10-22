package io.github.hanhy06.emote.examples;

import io.github.hanhy06.emote.api.EmoteCallbacks;
import io.github.hanhy06.emote.api.PlaybackContext;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Spawns an entity and moves it to an animation node on each playback tick.
 * Set the callback payload to the exported node ID of the locator to follow.
 */
public final class EntityFollowExample {
    // Emote cleans up entities with this tag after a server restart.
    private static final String RUNTIME_ENTITY_TAG = "emote.runtime";
    private static final double ALLAY_SCALE = 0.35D;

    public final EmoteCallbacks butterflyCallbacks = new EmoteCallbacks() {
        @Override
        public void onStart(PlaybackContext context) {
            spawnAllay(context);
        }

        @Override
        public void onTick(PlaybackContext context) {
            moveEntity(context, 0.35F);
        }

        @Override
        public void onClose(PlaybackContext context) {
            removeEntity(context, true);
        }
    };

    public final EmoteCallbacks batCallbacks = new EmoteCallbacks() {
        @Override
        public void onTick(PlaybackContext context) {
            Integer animationTick = context.getAnimationTick();
            if (animationTick == null) {
                return;
            }

            // This sample only displays the bat during a particular part of the clip.
            if (animationTick >= 25 && animationTick < 210) {
                if (context.getUserState() == null) {
                    spawnBat(context);
                }
                moveEntity(context, 0.6F);
            } else if (animationTick >= 210 && context.getUserState() != null) {
                moveEntity(context, 0.6F);
                removeEntity(context, true);
            }
        }

        @Override
        public void onClose(PlaybackContext context) {
            removeEntity(context, false);
        }
    };

    private void spawnAllay(PlaybackContext context) {
        removeEntity(context, false);

        ServerLevel level = context.getWorld();
        Allay allay = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        if (allay == null) {
            throw new IllegalStateException("Failed to create the idle butterfly Allay");
        }

        // User state belongs to this callback invocation, not to all players using it.
        context.setUserState(allay);
        Vec3 origin = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        allay.snapTo(
            origin.x, origin.y, origin.z,
            context.getActor("actor").orElseThrow().getYRot(), 0.0F
        );
        allay.setNoAi(true);
        allay.setPermanentlyInvulnerable(true);
        allay.setSilent(true);
        allay.setCanPickUpLoot(false);
        allay.addTag(RUNTIME_ENTITY_TAG);
        Objects.requireNonNull(allay.getAttribute(Attributes.SCALE), "Allay scale attribute").setBaseValue(ALLAY_SCALE);

        if (!level.addFreshEntity(allay)) {
            allay.discard();
            throw new IllegalStateException("Failed to add the idle butterfly Allay to the level");
        }
        level.sendParticles(
            ParticleTypes.WHITE_SMOKE,
            allay.getX(), allay.getY(0.5D), allay.getZ(),
            7, 0.08D, 0.08D, 0.08D, 0.02D
        );
    }

    private void spawnBat(PlaybackContext context) {
        removeEntity(context, false);

        ServerLevel level = context.getWorld();
        Bat bat = EntityTypes.BAT.create(level, EntitySpawnReason.COMMAND);
        if (bat == null) {
            throw new IllegalStateException("Failed to create the idle Bat");
        }

        context.setUserState(bat);
        Vec3 origin = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        bat.snapTo(
            origin.x, origin.y, origin.z,
            context.getActor("actor").orElseThrow().getYRot(), 0.0F
        );
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPermanentlyInvulnerable(true);
        bat.setSilent(true);
        bat.setResting(false);
        bat.addTag(RUNTIME_ENTITY_TAG);

        if (!level.addFreshEntity(bat)) {
            bat.discard();
            throw new IllegalStateException("Failed to add the idle Bat to the level");
        }
        level.sendParticles(
            ParticleTypes.SMOKE,
            bat.getX(), bat.getY(0.5D), bat.getZ(),
            12, 0.16D, 0.16D, 0.16D, 0.02D
        );
    }

    private void moveEntity(PlaybackContext context, float interpolationSpeed) {
        if (!(context.getUserState() instanceof LivingEntity entity) || entity.isRemoved()) {
            return;
        }

        // The API returns world coordinates, including the playback's position and rotation.
        Vec3 destination = context.getNodeWorldPosition(context.getPayload()).orElseThrow();
        Vec3 movement = destination.subtract(entity.position());
        double horizontalDistance = movement.horizontalDistance();
        if (horizontalDistance > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(interpolationSpeed, entity.getYRot(), targetYaw);
            entity.setYRot(yaw);
            entity.setYBodyRot(yaw);
            entity.setYHeadRot(yaw);
            entity.setXRot((float) Math.clamp(
                -(Mth.atan2(movement.y, horizontalDistance) * Mth.RAD_TO_DEG),
                -35.0D, 35.0D
            ));
        }
        entity.teleportTo(destination.x, destination.y, destination.z);
    }

    private void removeEntity(PlaybackContext context, boolean particles) {
        if (!(context.getUserState() instanceof Entity entity)) {
            return;
        }

        // onClose also runs when playback is interrupted; always release the spawned entity.
        context.setUserState(null);
        if (particles && !entity.isRemoved() && entity.level() instanceof ServerLevel level) {
            if (entity instanceof Allay) {
                level.sendParticles(
                    ParticleTypes.WHITE_SMOKE,
                    entity.getX(), entity.getY(0.5D), entity.getZ(),
                    7, 0.08D, 0.08D, 0.08D, 0.02D
                );
            } else if (entity instanceof Bat) {
                level.sendParticles(
                    ParticleTypes.SMOKE,
                    entity.getX(), entity.getY(0.5D), entity.getZ(),
                    12, 0.16D, 0.16D, 0.16D, 0.02D
                );
            }
        }
        entity.discard();
    }

}
