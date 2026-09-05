package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

public final class PlayerMolangQueries {
    public static final Source EMPTY = session -> {
        setPlayerQueries(session, PlayerQueryValues.EMPTY);
        setItemQueries(
            session,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY
        );
        setScoreboardQuery(session, objective -> 0.0D);
    };

    private PlayerMolangQueries() {
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
                player.position(),
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
                player.invulnerableTime,
                player.experienceLevel,
                maxUseTicks,
                remainingUseTicks,
                player.isBlocking(),
                usingItem && player.getUseItem().getUseAnimation() == ItemUseAnimation.EAT,
                player.getLastClientInput().jump(),
                player.isVisuallySwimming() && !player.isSwimming(),
                player.isInvisible(),
                player.hasEffect(MobEffects.LEVITATION),
                Mth.wrapDegrees(player.getYRot() - player.yRotO),
                Math.max(0, player.getRemainingFireTicks()) / 20.0D
            );
            setPlayerQueries(session, values);
            setItemQueries(
                session,
                ItemQueryValue.of(player.getMainHandItem()),
                ItemQueryValue.of(player.getOffhandItem()),
                ItemQueryValue.of(player.getItemBySlot(EquipmentSlot.HEAD)),
                ItemQueryValue.of(player.getItemBySlot(EquipmentSlot.CHEST)),
                ItemQueryValue.of(player.getItemBySlot(EquipmentSlot.LEGS)),
                ItemQueryValue.of(player.getItemBySlot(EquipmentSlot.FEET))
            );
            setScoreboardQuery(session, objectiveName -> {
                var scoreboard = player.level().getScoreboard();
                var objective = scoreboard.getObjective(objectiveName);
                if (objective == null) {
                    return 0.0D;
                }
                var score = scoreboard.getPlayerScoreInfo(player, objective);
                return score == null ? 0.0D : score.value();
            });
        };
    }

    static void setScoreboardQuery(MolangEngine.Session session, ToDoubleFunction<String> scoreLookup) {
        session.setQueryFunction("scoreboard", arguments -> MolangEngine.QueryValue.number(scoreLookup.applyAsDouble(arguments.string(0))));
    }

    static void setItemQueries(
        MolangEngine.Session session,
        ItemQueryValue mainHand,
        ItemQueryValue offHand,
        ItemQueryValue head,
        ItemQueryValue chest,
        ItemQueryValue legs,
        ItemQueryValue feet
    ) {
        ItemQueryValue[] equipment = {mainHand, offHand, head, chest, legs, feet};
        session.setQueryFunction("is_item_equipped", mainHand.empty() ? 0.0D : 1.0D, arguments ->
            MolangEngine.QueryValue.bool(!itemInSlot(arguments, 0, equipment).empty())
        );
        session.setQueryFunction("item_is_charged", mainHand.charged() ? 1.0D : 0.0D, arguments ->
            MolangEngine.QueryValue.bool(itemInSlot(arguments, 0, equipment).charged())
        );
        session.setQueryFunction("is_item_name_any", arguments -> {
            ItemQueryValue item = itemInSlot(arguments, 0, equipment);
            int firstName = arguments.size() > 1 && arguments.isNumber(1) ? 2 : 1;
            for (int i = firstName; i < arguments.size(); i++) {
                if (arguments.isString(i) && item.name().equals(arguments.string(i))) {
                    return MolangEngine.QueryValue.bool(true);
                }
            }
            return MolangEngine.QueryValue.bool(false);
        });
    }

    private static ItemQueryValue itemInSlot(MolangEngine.QueryArguments arguments, int index, ItemQueryValue[] equipment) {
        if (arguments.size() <= index) {
            return equipment[0];
        }
        if (arguments.isNumber(index)) {
            return arguments.number(index) == 1.0D ? equipment[1] : equipment[0];
        }
        return switch (arguments.string(index)) {
            case "off_hand", "slot.weapon.offhand" -> equipment[1];
            case "slot.armor.head" -> equipment[2];
            case "slot.armor.chest" -> equipment[3];
            case "slot.armor.legs" -> equipment[4];
            case "slot.armor.feet" -> equipment[5];
            case "main_hand", "slot.weapon.mainhand", "slot.weapon" -> equipment[0];
            default -> ItemQueryValue.EMPTY;
        };
    }

    record ItemQueryValue(String name, boolean charged) {
        private static final ItemQueryValue EMPTY = new ItemQueryValue("", false);

        private static ItemQueryValue of(ItemStack item) {
            return item.isEmpty()
                ? EMPTY
                : new ItemQueryValue(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), CrossbowItem.isCharged(item));
        }

        private boolean empty() {
            return this.name.isEmpty();
        }
    }

    private static void setPlayerQueries(MolangEngine.Session session, PlayerQueryValues values) {
        Vec3 movement = values.movement();
        setSpatialQueries(session, values.position(), movement);
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
        session.setQuery("blocking", values.blocking() ? 1.0D : 0.0D);
        session.setQuery("is_eating", values.eating() ? 1.0D : 0.0D);
        session.setQuery("is_jumping", values.jumping() ? 1.0D : 0.0D);
        session.setQuery("is_crawling", values.crawling() ? 1.0D : 0.0D);
        session.setQuery("is_invisible", values.invisible() ? 1.0D : 0.0D);
        session.setQuery("is_levitating", values.levitating() ? 1.0D : 0.0D);
        session.setQuery("yaw_speed", values.yawSpeed());
        session.setQuery("on_fire_time", values.onFireTime());
    }

    static void setSpatialQueries(MolangEngine.Session session, Vec3 position, Vec3 movement) {
        Vec3 direction = movement.lengthSqr() == 0.0D ? Vec3.ZERO : movement.normalize();
        session.setQueryFunction("position", arguments -> MolangEngine.QueryValue.number(axis(position, arguments.number(0))));
        session.setQueryFunction("position_delta", arguments -> MolangEngine.QueryValue.number(axis(movement, arguments.number(0))));
        session.setQueryFunction("movement_direction", arguments -> MolangEngine.QueryValue.number(axis(direction, arguments.number(0))));
    }

    private static double axis(Vec3 vector, double axis) {
        return switch ((int) axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> 0.0D;
        };
    }

    private record PlayerQueryValues(
        Vec3 position,
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
        int remainingUseTicks,
        boolean blocking,
        boolean eating,
        boolean jumping,
        boolean crawling,
        boolean invisible,
        boolean levitating,
        double yawSpeed,
        double onFireTime
    ) {
        private static final PlayerQueryValues EMPTY = new PlayerQueryValues(
            Vec3.ZERO,
            Vec3.ZERO,
            0.0D, 0.0D, 0.0D, 0.0D,
            0.0D, 0.0D, 0.0D,
            false, false, false, false, false, false, false, false, false, false, false, false,
            0.0D, 0.0D,
            false, false, false, false, false,
            0, 0, 0, 0, 0, 0,
            false, false, false, false, false, false,
            0.0D, 0.0D
        );
    }

    @FunctionalInterface
    public interface Source {
        void apply(MolangEngine.Session session);
    }
}
