package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.playback.molang.MolangQueries.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.github.hanhy06.emote.playback.molang.MolangQueries.*;

public final class PlayerMolangQueries {
    private PlayerMolangQueries() {}
    public static MolangQuerySource forPlayer(ServerPlayer player) {
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
            var movement = player.getKnownMovement();
            setSpatialQueries(session, player.position(), movement);
            session.setQuery("target_x_rotation", xRotation);
            session.setQuery("target_y_rotation", Mth.wrapDegrees(headYRotation - bodyYRotation));
            session.setQuery("body_x_rotation", xRotation);
            session.setQuery("body_y_rotation", bodyYRotation);
            session.setQuery("head_x_rotation", xRotation);
            session.setQuery("head_y_rotation", headYRotation);
            session.setQuery("eye_target_x_rotation", xRotation);
            session.setQuery("eye_target_y_rotation", headYRotation);
            session.setQuery("ground_speed", movement.horizontalDistance() * 20.0D);
            session.setQuery("vertical_speed", movement.y * 20.0D);
            session.setQuery("modified_distance_moved", player.walkAnimation.position());
            session.setQuery("walk_distance", player.moveDist);
            session.setQuery("is_moving", movement.lengthSqr() > MOVEMENT_EPSILON_SQUARED ? 1.0D : 0.0D);
            session.setQuery("is_on_ground", player.onGround() ? 1.0D : 0.0D);
            session.setQuery("is_sneaking", player.isCrouching() ? 1.0D : 0.0D);
            session.setQuery("is_sprinting", player.isSprinting() ? 1.0D : 0.0D);
            session.setQuery("is_swimming", player.isSwimming() ? 1.0D : 0.0D);
            session.setQuery("is_gliding", player.isFallFlying() ? 1.0D : 0.0D);
            session.setQuery("is_riding", player.isPassenger() ? 1.0D : 0.0D);
            session.setQuery("is_using_item", usingItem ? 1.0D : 0.0D);
            session.setQuery("is_sleeping", player.isSleeping() ? 1.0D : 0.0D);
            session.setQuery("is_emoting", 1.0D);
            session.setQuery("item_is_charged", CrossbowItem.isCharged(player.getMainHandItem()) ? 1.0D : 0.0D);
            session.setQuery("sleep_rotation", sleepRotation);
            session.setQuery("is_on_fire", player.isOnFire() ? 1.0D : 0.0D);
            session.setQuery("is_in_water", player.isInWater() ? 1.0D : 0.0D);
            session.setQuery("health", player.getHealth());
            session.setQuery("max_health", player.getMaxHealth());
            session.setQuery("is_alive", player.isAlive() ? 1.0D : 0.0D);
            session.setQuery("is_spectator", player.isSpectator() ? 1.0D : 0.0D);
            session.setQuery("head_is_in_water", player.isEyeInFluid(FluidTags.WATER) ? 1.0D : 0.0D);
            session.setQuery("is_in_lava", player.isInLava() ? 1.0D : 0.0D);
            session.setQuery("is_in_water_or_rain", player.isInWaterOrRain() ? 1.0D : 0.0D);
            session.setQuery("hurt_time", player.hurtTime);
            session.setQuery("death_ticks", player.deathTime);
            session.setQuery("invulnerable_ticks", player.getInvulnerableTime());
            session.setQuery("player_level", player.experienceLevel);
            session.setQuery("item_in_use_duration", (maxUseTicks - remainingUseTicks) / 20.0D);
            session.setQuery("item_remaining_use_duration", remainingUseTicks / 20.0D);
            session.setQuery("item_max_use_duration", maxUseTicks / 20.0D);
            session.setQuery("blocking", player.isBlocking() ? 1.0D : 0.0D);
            session.setQuery("is_eating", usingItem && player.getUseItem().getUseAnimation() == ItemUseAnimation.EAT ? 1.0D : 0.0D);
            session.setQuery("is_jumping", player.getLastClientInput().jump() ? 1.0D : 0.0D);
            session.setQuery("is_crawling", player.isVisuallySwimming() && !player.isSwimming() ? 1.0D : 0.0D);
            session.setQuery("is_invisible", player.isInvisible() ? 1.0D : 0.0D);
            session.setQuery("is_levitating", player.hasEffect(MobEffects.LEVITATION) ? 1.0D : 0.0D);
            session.setQuery("yaw_speed", Mth.wrapDegrees(player.getYRot() - player.yRotO));
            session.setQuery("on_fire_time", Math.max(0, player.getRemainingFireTicks()) / 20.0D);
            setItemQueries(
                session,
                itemQueryValue(player.getMainHandItem()),
                itemQueryValue(player.getOffhandItem()),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.HEAD)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.CHEST)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.LEGS)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.FEET))
            );
            var scoreboard = player.level().getScoreboard();
            Map<String, Double> scores = new HashMap<>();
            for (var objective : scoreboard.getObjectives()) {
                var score = scoreboard.getPlayerScoreInfo(player, objective);
                scores.put(objective.getName(), score == null ? 0.0D : (double) score.value());
            }
            setScoreboardQuery(session, objectiveName -> scores.getOrDefault(objectiveName, 0.0D));
        };
    }

    private static ItemQueryValue itemQueryValue(ItemStack item) {
        return item.isEmpty() ? new ItemQueryValue("", false)
            : new ItemQueryValue(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), CrossbowItem.isCharged(item));
    }
}
