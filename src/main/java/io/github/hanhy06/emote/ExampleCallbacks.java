package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.*;
import io.github.hanhy06.emote.playback.runtime.PlaybackEntityController;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Registers the example callbacks shipped with the mod. The idle butterfly callback is declared as follows:
 *
 * <pre>{@code
 * {
 *   "source": {"type": "server"},
 *   "origin": {"type": "node", "node": "butterfly"},
 *   "commands": [],
 *   "callbacks": [{"name": "emote:idle_butterfly_callback"}]
 * }
 * }</pre>
 */
public final class ExampleCallbacks {
    public static final Identifier IDLE_BUTTERFLY_CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");
    public static final Identifier IDLE_BAT_CALLBACK_ID = Identifier.parse("emote:idle_bat_callback");

    private static final double ALLAY_SCALE = 0.35D;

    private final Map<UUID, Entity> entitiesByPlayer = new HashMap<>();
    private final List<ListenerRegistration> registrations;

    private boolean registered = true;

    private ExampleCallbacks(EmoteApi api) {
        this.registrations = List.of(
            api.addCallbackListener(IDLE_BUTTERFLY_CALLBACK_ID, this::handleIdleButterfly),
            api.addCallbackListener(IDLE_BAT_CALLBACK_ID, this::handleIdleBat),
            api.addPlaybackListener(new EmotePlaybackListener() {
                @Override
                public void onStopped(PlaybackInfo playback, PlaybackStopReason reason) {
                    removeEntity(playback.playerUuid(), true);
                }
            })
        );
    }

    public static ExampleCallbacks registerAll(EmoteApi api) {
        return new ExampleCallbacks(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        if (!this.registered) return false;
        this.registered = false;

        boolean removed = false;
        for (ListenerRegistration registration : this.registrations) {
            removed |= registration.unregister();
        }
        this.entitiesByPlayer.values().forEach(Entity::discard);
        this.entitiesByPlayer.clear();
        return removed;
    }

    private void handleIdleButterfly(EmoteCallbackEvent event) {
        switch (event.phase()) {
            case START -> spawnAllay(event);
            case TIMELINE -> moveAllay(event);
            case STOP -> removeEntity(event.player().getUUID(), true);
            case LOOP -> {}
        }
    }

    private void spawnAllay(EmoteCallbackEvent event) {
        UUID playerUuid = event.player().getUUID();
        removeEntity(playerUuid, false);

        ServerLevel level = event.player().level();
        Allay allay = EntityTypes.ALLAY.create(level, EntitySpawnReason.COMMAND);
        if (allay == null) {
            throw new IllegalStateException("Failed to create the idle butterfly Allay");
        }

        allay.snapTo(event.origin().x, event.origin().y, event.origin().z, event.player().getYRot(), 0.0F);
        allay.setNoAi(true);
        allay.setPermanentlyInvulnerable(true);
        allay.setSilent(true);
        allay.setCanPickUpLoot(false);
        allay.addTag(PlaybackEntityController.RUNTIME_TAG);
        Objects.requireNonNull(allay.getAttribute(Attributes.SCALE), "Allay scale attribute").setBaseValue(ALLAY_SCALE);

        if (!level.addFreshEntity(allay)) {
            allay.discard();
            throw new IllegalStateException("Failed to add the idle butterfly Allay to the level");
        }
        level.sendParticles(ParticleTypes.WHITE_SMOKE, allay.getX(), allay.getY(0.5D), allay.getZ(), 7, 0.08D, 0.08D, 0.08D, 0.02D);
        this.entitiesByPlayer.put(playerUuid, allay);
    }

    private void moveAllay(EmoteCallbackEvent event) {
        Entity entity = this.entitiesByPlayer.get(event.player().getUUID());
        if (!(entity instanceof Allay allay) || allay.isRemoved()) return;

        Vec3 destination = event.origin();
        Vec3 movement = destination.subtract(allay.position());
        if (movement.horizontalDistanceSqr() > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(0.35F, allay.getYRot(), targetYaw);
            allay.setYRot(yaw);
            allay.setYBodyRot(yaw);
            allay.setYHeadRot(yaw);
        }
        allay.teleportTo(destination.x, destination.y, destination.z);
    }

    private void handleIdleBat(EmoteCallbackEvent event) {
        if (event.phase() == EmoteCallbackPhase.STOP) {
            removeEntity(event.player().getUUID(), false);
            return;
        }
        if (event.phase() != EmoteCallbackPhase.TIMELINE) return;

        switch (event.payload()) {
            case "spawn" -> spawnBat(event);
            case "move" -> moveBat(event);
            case "remove" -> {
                moveBat(event);
                removeEntity(event.player().getUUID(), true);
            }
            default -> throw new IllegalArgumentException("Unknown idle bat callback payload: " + event.payload());
        }
    }

    private void spawnBat(EmoteCallbackEvent event) {
        UUID playerUuid = event.player().getUUID();
        removeEntity(playerUuid, false);

        ServerLevel level = event.player().level();
        Bat bat = EntityTypes.BAT.create(level, EntitySpawnReason.COMMAND);
        if (bat == null) throw new IllegalStateException("Failed to create the idle Bat");

        bat.snapTo(event.origin().x, event.origin().y, event.origin().z, event.player().getYRot(), 0.0F);
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPermanentlyInvulnerable(true);
        bat.setSilent(true);
        bat.setResting(false);
        bat.addTag(PlaybackEntityController.RUNTIME_TAG);

        if (!level.addFreshEntity(bat)) {
            bat.discard();
            throw new IllegalStateException("Failed to add the idle Bat to the level");
        }
        level.sendParticles(ParticleTypes.SMOKE, bat.getX(), bat.getY(0.5D), bat.getZ(), 12, 0.16D, 0.16D, 0.16D, 0.02D);
        this.entitiesByPlayer.put(playerUuid, bat);
    }

    private void moveBat(EmoteCallbackEvent event) {
        Entity entity = this.entitiesByPlayer.get(event.player().getUUID());
        if (!(entity instanceof Bat bat) || bat.isRemoved()) return;

        Vec3 destination = event.origin();
        Vec3 movement = destination.subtract(bat.position());
        double horizontalDistance = movement.horizontalDistance();
        if (horizontalDistance > 1.0E-6D) {
            float targetYaw = (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.rotLerp(0.6F, bat.getYRot(), targetYaw);
            bat.setYRot(yaw);
            bat.setYBodyRot(yaw);
            bat.setYHeadRot(yaw);
            bat.setXRot((float) Mth.clamp(-(Mth.atan2(movement.y, horizontalDistance) * Mth.RAD_TO_DEG), -35.0D, 35.0D));
        }
        bat.teleportTo(destination.x, destination.y, destination.z);
    }

    private void removeEntity(UUID playerUuid, boolean particles) {
        Entity entity = this.entitiesByPlayer.remove(playerUuid);
        if (entity == null) return;
        if (particles && !entity.isRemoved() && entity.level() instanceof ServerLevel level) {
            if (entity instanceof Allay) {
                level.sendParticles(ParticleTypes.WHITE_SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 7, 0.08D, 0.08D, 0.08D, 0.02D);
            } else if (entity instanceof Bat) {
                level.sendParticles(ParticleTypes.SMOKE, entity.getX(), entity.getY(0.5D), entity.getZ(), 12, 0.16D, 0.16D, 0.16D, 0.02D);
            }
        }
        entity.discard();
    }
}
