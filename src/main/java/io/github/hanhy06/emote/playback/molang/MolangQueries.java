package io.github.hanhy06.emote.playback.molang;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

public final class MolangQueries {
    public static final Set<String> SUPPORTED_NAMES = Set.of(
        "anim_time",
        "anim_time_ticks",
        "anim_length",
        "delta_time",
        "loop_count",
        "key_frame_lerp_time",
        "life_time",
        "target_x_rotation",
        "target_y_rotation",
        "body_x_rotation",
        "body_y_rotation",
        "head_x_rotation",
        "head_y_rotation",
        "eye_target_x_rotation",
        "eye_target_y_rotation",
        "ground_speed",
        "vertical_speed",
        "modified_distance_moved",
        "walk_distance",
        "is_moving",
        "is_on_ground",
        "is_sneaking",
        "is_sprinting",
        "is_swimming",
        "is_gliding",
        "is_riding",
        "is_using_item",
        "is_sleeping",
        "is_emoting",
        "item_is_charged",
        "sleep_rotation",
        "is_on_fire",
        "is_in_water",
        "health",
        "max_health",
        "is_alive",
        "is_spectator",
        "head_is_in_water",
        "is_in_lava",
        "is_in_water_or_rain",
        "hurt_time",
        "death_ticks",
        "invulnerable_ticks",
        "player_level",
        "item_in_use_duration",
        "item_remaining_use_duration",
        "item_max_use_duration"
    );
    public static final Source EMPTY = session -> setPlayerQueries(session, PlayerQueryValues.EMPTY);

    private MolangQueries() {
    }

    public static Source forPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return session -> {
            double bodyYRotation = Mth.wrapDegrees(player.yBodyRot);
            double headYRotation = Mth.wrapDegrees(player.yHeadRot);
            double xRotation = player.getXRot();
            var bedOrientation = player.getBedOrientation();
            double sleepRotation = bedOrientation == null ? 0.0D : bedOrientation.toYRot();
            boolean usingItem = player.isUsingItem();
            int maxUseTicks = usingItem ? player.getUseItem().getUseDuration(player) : 0;
            int remainingUseTicks = usingItem ? Mth.clamp(player.getUseItemRemainingTicks(), 0, maxUseTicks) : 0;
            PlayerQueryValues values = new PlayerQueryValues(
                player.getKnownMovement(),
                xRotation,
                bodyYRotation,
                headYRotation,
                Mth.wrapDegrees(headYRotation - bodyYRotation),
                player.walkAnimation.position(),
                player.moveDist,
                sleepRotation,
                player.onGround(),
                player.isCrouching(),
                player.isSprinting(),
                player.isSwimming(),
                player.isFallFlying(),
                player.isPassenger(),
                usingItem,
                player.isSleeping(),
                true,
                CrossbowItem.isCharged(player.getMainHandItem()),
                player.isOnFire(),
                player.isInWater(),
                player.getHealth(),
                player.getMaxHealth(),
                player.isAlive(),
                player.isSpectator(),
                player.isEyeInFluid(FluidTags.WATER),
                player.isInLava(),
                player.isInWaterOrRain(),
                player.hurtTime,
                player.deathTime,
                player.getInvulnerableTime(),
                player.experienceLevel,
                maxUseTicks,
                remainingUseTicks
            );
            setPlayerQueries(session, values);
        };
    }

    private static void setPlayerQueries(MolangEngine.Session session, PlayerQueryValues values) {
        Vec3 movement = values.movement();
        session.setQuery("target_x_rotation", values.xRotation());
        session.setQuery("target_y_rotation", values.targetYRotation());
        session.setQuery("body_x_rotation", values.xRotation());
        session.setQuery("body_y_rotation", values.bodyYRotation());
        session.setQuery("head_x_rotation", values.xRotation());
        session.setQuery("head_y_rotation", values.headYRotation());
        session.setQuery("eye_target_x_rotation", values.xRotation());
        session.setQuery("eye_target_y_rotation", values.headYRotation());
        session.setQuery("ground_speed", movement.horizontalDistance() * 20.0D);
        session.setQuery("vertical_speed", movement.y * 20.0D);
        session.setQuery("modified_distance_moved", values.modifiedDistanceMoved());
        session.setQuery("walk_distance", values.walkDistance());
        session.setQuery("is_moving", movement.lengthSqr() > 1.0E-10D ? 1.0D : 0.0D);
        session.setQuery("is_on_ground", values.onGround() ? 1.0D : 0.0D);
        session.setQuery("is_sneaking", values.sneaking() ? 1.0D : 0.0D);
        session.setQuery("is_sprinting", values.sprinting() ? 1.0D : 0.0D);
        session.setQuery("is_swimming", values.swimming() ? 1.0D : 0.0D);
        session.setQuery("is_gliding", values.gliding() ? 1.0D : 0.0D);
        session.setQuery("is_riding", values.riding() ? 1.0D : 0.0D);
        session.setQuery("is_using_item", values.usingItem() ? 1.0D : 0.0D);
        session.setQuery("is_sleeping", values.sleeping() ? 1.0D : 0.0D);
        session.setQuery("is_emoting", values.emoting() ? 1.0D : 0.0D);
        session.setQuery("item_is_charged", values.itemCharged() ? 1.0D : 0.0D);
        session.setQuery("sleep_rotation", values.sleepRotation());
        session.setQuery("is_on_fire", values.onFire() ? 1.0D : 0.0D);
        session.setQuery("is_in_water", values.inWater() ? 1.0D : 0.0D);
        session.setQuery("health", values.health());
        session.setQuery("max_health", values.maxHealth());
        session.setQuery("is_alive", values.alive() ? 1.0D : 0.0D);
        session.setQuery("is_spectator", values.spectator() ? 1.0D : 0.0D);
        session.setQuery("head_is_in_water", values.headInWater() ? 1.0D : 0.0D);
        session.setQuery("is_in_lava", values.inLava() ? 1.0D : 0.0D);
        session.setQuery("is_in_water_or_rain", values.inWaterOrRain() ? 1.0D : 0.0D);
        session.setQuery("hurt_time", values.hurtTicks());
        session.setQuery("death_ticks", values.deathTicks());
        session.setQuery("invulnerable_ticks", values.invulnerableTicks());
        session.setQuery("player_level", values.playerLevel());
        session.setQuery("item_in_use_duration", (values.maxUseTicks() - values.remainingUseTicks()) / 20.0D);
        session.setQuery("item_remaining_use_duration", values.remainingUseTicks() / 20.0D);
        session.setQuery("item_max_use_duration", values.maxUseTicks() / 20.0D);
    }

    private record PlayerQueryValues(
        Vec3 movement,
        double xRotation,
        double bodyYRotation,
        double headYRotation,
        double targetYRotation,
        double modifiedDistanceMoved,
        double walkDistance,
        double sleepRotation,
        boolean onGround,
        boolean sneaking,
        boolean sprinting,
        boolean swimming,
        boolean gliding,
        boolean riding,
        boolean usingItem,
        boolean sleeping,
        boolean emoting,
        boolean itemCharged,
        boolean onFire,
        boolean inWater,
        double health,
        double maxHealth,
        boolean alive,
        boolean spectator,
        boolean headInWater,
        boolean inLava,
        boolean inWaterOrRain,
        int hurtTicks,
        int deathTicks,
        int invulnerableTicks,
        int playerLevel,
        int maxUseTicks,
        int remainingUseTicks
    ) {
        private static final PlayerQueryValues EMPTY = new PlayerQueryValues(
            Vec3.ZERO,
            0.0D, 0.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 0.0D,
            false, false, false, false, false, false, false, false, false, false, false, false,
            0.0D, 0.0D,
            false, false, false, false, false,
            0, 0, 0, 0, 0, 0
        );
    }

    @FunctionalInterface
    public interface Source {
        void apply(MolangEngine.Session session);
    }
}
