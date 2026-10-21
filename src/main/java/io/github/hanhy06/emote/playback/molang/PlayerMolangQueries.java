package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.hanhy06.emote.playback.molang.MolangQueries.*;

public final class PlayerMolangQueries {
    private PlayerMolangQueries() {}
    public static MolangQuerySource forPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return (session, queries) -> apply(player, session, queries);
    }

    private static void apply(ServerPlayer player, MolangEngine.Session session, Set<String> queries) {
        if (queries.isEmpty()) return;
        double bodyYRotation = queries.contains("body_y_rotation") || queries.contains("target_y_rotation") ? Mth.wrapDegrees(player.yBodyRot) : 0;
        double headYRotation = queries.contains("head_y_rotation") || queries.contains("eye_target_y_rotation") || queries.contains("target_y_rotation") ? Mth.wrapDegrees(player.yHeadRot) : 0;
        double xRotation = queries.contains("target_x_rotation") || queries.contains("body_x_rotation") || queries.contains("head_x_rotation") || queries.contains("eye_target_x_rotation") ? player.getXRot() : 0;
        var bedOrientation = queries.contains("sleep_rotation") ? player.getBedOrientation() : null;
        double sleepRotation = bedOrientation == null ? 0.0D : bedOrientation.toYRot();
        boolean itemTiming = queries.contains("item_in_use_duration") || queries.contains("item_remaining_use_duration") || queries.contains("item_max_use_duration");
        boolean usingItem = (itemTiming || queries.contains("is_using_item") || queries.contains("is_eating")) && player.isUsingItem();
        int maxUseTicks = itemTiming && usingItem ? player.getUseItem().getUseDuration(player) : 0;
        int remainingUseTicks = itemTiming && usingItem ? Mth.clamp(player.getUseItemRemainingTicks(), 0, maxUseTicks) : 0;
        var movement = queries.contains("position_delta") || queries.contains("movement_direction") || queries.contains("ground_speed")
            || queries.contains("vertical_speed") || queries.contains("is_moving") ? player.getKnownMovement() : Vec3.ZERO;
        setSpatialQueries(session, queries.contains("position") ? player.position() : Vec3.ZERO, movement, queries);
        if (queries.contains("target_x_rotation")) session.setQuery("target_x_rotation", xRotation);
        if (queries.contains("target_y_rotation")) session.setQuery("target_y_rotation", Mth.wrapDegrees(headYRotation - bodyYRotation));
        if (queries.contains("body_x_rotation")) session.setQuery("body_x_rotation", xRotation);
        if (queries.contains("body_y_rotation")) session.setQuery("body_y_rotation", bodyYRotation);
        if (queries.contains("head_x_rotation")) session.setQuery("head_x_rotation", xRotation);
        if (queries.contains("head_y_rotation")) session.setQuery("head_y_rotation", headYRotation);
        if (queries.contains("eye_target_x_rotation")) session.setQuery("eye_target_x_rotation", xRotation);
        if (queries.contains("eye_target_y_rotation")) session.setQuery("eye_target_y_rotation", headYRotation);
        if (queries.contains("ground_speed")) session.setQuery("ground_speed", movement.horizontalDistance() * 20.0D);
        if (queries.contains("vertical_speed")) session.setQuery("vertical_speed", movement.y * 20.0D);
        if (queries.contains("modified_distance_moved")) session.setQuery("modified_distance_moved", player.walkAnimation.position());
        if (queries.contains("walk_distance")) session.setQuery("walk_distance", player.moveDist);
        if (queries.contains("is_moving")) session.setQuery("is_moving", movement.lengthSqr() > MOVEMENT_EPSILON_SQUARED ? 1.0D : 0.0D);
        if (queries.contains("is_on_ground")) session.setQuery("is_on_ground", player.onGround() ? 1.0D : 0.0D);
        if (queries.contains("is_sneaking")) session.setQuery("is_sneaking", player.isCrouching() ? 1.0D : 0.0D);
        if (queries.contains("is_sprinting")) session.setQuery("is_sprinting", player.isSprinting() ? 1.0D : 0.0D);
        if (queries.contains("is_swimming")) session.setQuery("is_swimming", player.isSwimming() ? 1.0D : 0.0D);
        if (queries.contains("is_gliding")) session.setQuery("is_gliding", player.isFallFlying() ? 1.0D : 0.0D);
        if (queries.contains("is_riding")) session.setQuery("is_riding", player.isPassenger() ? 1.0D : 0.0D);
        if (queries.contains("is_using_item")) session.setQuery("is_using_item", usingItem ? 1.0D : 0.0D);
        if (queries.contains("is_sleeping")) session.setQuery("is_sleeping", player.isSleeping() ? 1.0D : 0.0D);
        if (queries.contains("is_emoting")) session.setQuery("is_emoting", 1.0D);
        if (queries.contains("sleep_rotation")) session.setQuery("sleep_rotation", sleepRotation);
        if (queries.contains("is_on_fire")) session.setQuery("is_on_fire", player.isOnFire() ? 1.0D : 0.0D);
        if (queries.contains("is_in_water")) session.setQuery("is_in_water", player.isInWater() ? 1.0D : 0.0D);
        if (queries.contains("health")) session.setQuery("health", player.getHealth());
        if (queries.contains("max_health")) session.setQuery("max_health", player.getMaxHealth());
        if (queries.contains("is_alive")) session.setQuery("is_alive", player.isAlive() ? 1.0D : 0.0D);
        if (queries.contains("is_spectator")) session.setQuery("is_spectator", player.isSpectator() ? 1.0D : 0.0D);
        if (queries.contains("head_is_in_water")) session.setQuery("head_is_in_water", player.isEyeInFluid(FluidTags.WATER) ? 1.0D : 0.0D);
        if (queries.contains("is_in_lava")) session.setQuery("is_in_lava", player.isInLava() ? 1.0D : 0.0D);
        if (queries.contains("is_in_water_or_rain")) session.setQuery("is_in_water_or_rain", player.isInWaterOrRain() ? 1.0D : 0.0D);
        if (queries.contains("hurt_time")) session.setQuery("hurt_time", player.hurtTime);
        if (queries.contains("death_ticks")) session.setQuery("death_ticks", player.deathTime);
        if (queries.contains("invulnerable_ticks")) session.setQuery("invulnerable_ticks", player.invulnerableTime);
        if (queries.contains("player_level")) session.setQuery("player_level", player.experienceLevel);
        if (queries.contains("item_in_use_duration")) session.setQuery("item_in_use_duration", (maxUseTicks - remainingUseTicks) / 20.0D);
        if (queries.contains("item_remaining_use_duration")) session.setQuery("item_remaining_use_duration", remainingUseTicks / 20.0D);
        if (queries.contains("item_max_use_duration")) session.setQuery("item_max_use_duration", maxUseTicks / 20.0D);
        if (queries.contains("blocking")) session.setQuery("blocking", player.isBlocking() ? 1.0D : 0.0D);
        if (queries.contains("is_eating")) session.setQuery("is_eating", usingItem && player.getUseItem().getUseAnimation() == ItemUseAnimation.EAT ? 1.0D : 0.0D);
        if (queries.contains("is_jumping")) session.setQuery("is_jumping", player.getLastClientInput().jump() ? 1.0D : 0.0D);
        if (queries.contains("is_crawling")) session.setQuery("is_crawling", player.isVisuallySwimming() && !player.isSwimming() ? 1.0D : 0.0D);
        if (queries.contains("is_invisible")) session.setQuery("is_invisible", player.isInvisible() ? 1.0D : 0.0D);
        if (queries.contains("is_levitating")) session.setQuery("is_levitating", player.hasEffect(MobEffects.LEVITATION) ? 1.0D : 0.0D);
        if (queries.contains("yaw_speed")) session.setQuery("yaw_speed", Mth.wrapDegrees(player.getYRot() - player.yRotO));
        if (queries.contains("on_fire_time")) session.setQuery("on_fire_time", Math.max(0, player.getRemainingFireTicks()) / 20.0D);
        if (queries.contains("is_item_equipped") || queries.contains("item_is_charged") || queries.contains("is_item_name_any")) setItemQueries(
            session,
            queries,
            itemQueryValue(player.getMainHandItem()),
            itemQueryValue(player.getOffhandItem()),
            itemQueryValue(player.getItemBySlot(EquipmentSlot.HEAD)),
            itemQueryValue(player.getItemBySlot(EquipmentSlot.CHEST)),
            itemQueryValue(player.getItemBySlot(EquipmentSlot.LEGS)),
            itemQueryValue(player.getItemBySlot(EquipmentSlot.FEET))
        );
        if (queries.contains("scoreboard")) {
            var scoreboard = player.level().getScoreboard();
            Map<String, Double> scores = new HashMap<>();
            for (var objective : scoreboard.getObjectives()) {
                var score = scoreboard.getPlayerScoreInfo(player, objective);
                scores.put(objective.getName(), score == null ? 0.0D : (double) score.value());
            }
            setScoreboardQuery(session, objectiveName -> scores.getOrDefault(objectiveName, 0.0D));
        }
    }

    private static ItemQueryValue itemQueryValue(ItemStack item) {
        return item.isEmpty() ? new ItemQueryValue("", false)
            : new ItemQueryValue(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), CrossbowItem.isCharged(item));
    }
}
