package io.github.hanhy06.emote.playback.molang;

import net.minecraft.server.level.ServerPlayer;
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
        "ground_speed",
        "vertical_speed",
        "is_moving",
        "is_on_ground",
        "is_sprinting",
        "is_swimming",
        "is_gliding",
        "is_riding",
        "is_using_item",
        "is_on_fire",
        "is_in_water"
    );
    public static final Source EMPTY = session -> setPlayerQueries(
        session,
        Vec3.ZERO,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false
    );

    private MolangQueries() {
    }

    public static Source forPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return session -> setPlayerQueries(
            session,
            player.getKnownMovement(),
            player.onGround(),
            player.isSprinting(),
            player.isSwimming(),
            player.isFallFlying(),
            player.isPassenger(),
            player.isUsingItem(),
            player.isOnFire(),
            player.isInWater()
        );
    }

    private static void setPlayerQueries(
        MolangEngine.Session session,
        Vec3 movement,
        boolean onGround,
        boolean sprinting,
        boolean swimming,
        boolean gliding,
        boolean riding,
        boolean usingItem,
        boolean onFire,
        boolean inWater
    ) {
        session.setQuery("ground_speed", movement.horizontalDistance() * 20.0D);
        session.setQuery("vertical_speed", movement.y * 20.0D);
        session.setQuery("is_moving", movement.lengthSqr() > 1.0E-10D ? 1.0D : 0.0D);
        session.setQuery("is_on_ground", onGround ? 1.0D : 0.0D);
        session.setQuery("is_sprinting", sprinting ? 1.0D : 0.0D);
        session.setQuery("is_swimming", swimming ? 1.0D : 0.0D);
        session.setQuery("is_gliding", gliding ? 1.0D : 0.0D);
        session.setQuery("is_riding", riding ? 1.0D : 0.0D);
        session.setQuery("is_using_item", usingItem ? 1.0D : 0.0D);
        session.setQuery("is_on_fire", onFire ? 1.0D : 0.0D);
        session.setQuery("is_in_water", inWater ? 1.0D : 0.0D);
    }

    @FunctionalInterface
    public interface Source {
        void apply(MolangEngine.Session session);
    }
}
