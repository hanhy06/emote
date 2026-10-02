package io.github.hanhy06.emote.playback.molang;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.Objects;

import io.github.hanhy06.emote.playback.molang.MolangQueries.ItemQueryValue;
import io.github.hanhy06.emote.playback.molang.MolangQueries.PlayerQueryValues;
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
                player.getInvulnerableTime(),
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
                itemQueryValue(player.getMainHandItem()),
                itemQueryValue(player.getOffhandItem()),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.HEAD)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.CHEST)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.LEGS)),
                itemQueryValue(player.getItemBySlot(EquipmentSlot.FEET))
            );
            setScoreboardQuery(session, objectiveName -> scoreboardValue(player, objectiveName));
        };
    }

    private static double scoreboardValue(ServerPlayer player, String objectiveName) {
        var scoreboard = player.level().getScoreboard();
        var objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            return 0.0D;
        }
        var score = scoreboard.getPlayerScoreInfo(player, objective);
        return score == null ? 0.0D : score.value();
    }

    private static ItemQueryValue itemQueryValue(ItemStack item) {
        return item.isEmpty() ? new ItemQueryValue("", false)
            : new ItemQueryValue(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), CrossbowItem.isCharged(item));
    }
}
