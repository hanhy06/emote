package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleFunction;

public final class MolangQueries {
    static void applyEmpty(MolangEngine.Session session) {
        setSpatialQueries(session, Vec3.ZERO, Vec3.ZERO);
        session.setQuery("target_x_rotation", 0.0D);
        session.setQuery("target_y_rotation", 0.0D);
        session.setQuery("body_x_rotation", 0.0D);
        session.setQuery("body_y_rotation", 0.0D);
        session.setQuery("head_x_rotation", 0.0D);
        session.setQuery("head_y_rotation", 0.0D);
        session.setQuery("eye_target_x_rotation", 0.0D);
        session.setQuery("eye_target_y_rotation", 0.0D);
        session.setQuery("ground_speed", 0.0D);
        session.setQuery("vertical_speed", 0.0D);
        session.setQuery("modified_distance_moved", 0.0D);
        session.setQuery("walk_distance", 0.0D);
        session.setQuery("is_moving", 0.0D);
        session.setQuery("is_on_ground", 0.0D);
        session.setQuery("is_sneaking", 0.0D);
        session.setQuery("is_sprinting", 0.0D);
        session.setQuery("is_swimming", 0.0D);
        session.setQuery("is_gliding", 0.0D);
        session.setQuery("is_riding", 0.0D);
        session.setQuery("is_using_item", 0.0D);
        session.setQuery("is_sleeping", 0.0D);
        session.setQuery("is_emoting", 0.0D);
        session.setQuery("item_is_charged", 0.0D);
        session.setQuery("sleep_rotation", 0.0D);
        session.setQuery("is_on_fire", 0.0D);
        session.setQuery("is_in_water", 0.0D);
        session.setQuery("health", 0.0D);
        session.setQuery("max_health", 0.0D);
        session.setQuery("is_alive", 0.0D);
        session.setQuery("is_spectator", 0.0D);
        session.setQuery("head_is_in_water", 0.0D);
        session.setQuery("is_in_lava", 0.0D);
        session.setQuery("is_in_water_or_rain", 0.0D);
        session.setQuery("hurt_time", 0.0D);
        session.setQuery("death_ticks", 0.0D);
        session.setQuery("invulnerable_ticks", 0.0D);
        session.setQuery("player_level", 0.0D);
        session.setQuery("item_in_use_duration", 0.0D);
        session.setQuery("item_remaining_use_duration", 0.0D);
        session.setQuery("item_max_use_duration", 0.0D);
        session.setQuery("blocking", 0.0D);
        session.setQuery("is_eating", 0.0D);
        session.setQuery("is_jumping", 0.0D);
        session.setQuery("is_crawling", 0.0D);
        session.setQuery("is_invisible", 0.0D);
        session.setQuery("is_levitating", 0.0D);
        session.setQuery("yaw_speed", 0.0D);
        session.setQuery("on_fire_time", 0.0D);
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
    }
    static final double MOVEMENT_EPSILON_SQUARED = 1.0E-10D;

    private MolangQueries() {
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

        private boolean empty() {
            return this.name.isEmpty();
        }
    }

    static void setSpatialQueries(MolangEngine.Session session, Vec3 position, Vec3 movement) {
        Vec3 direction = movement.lengthSqr() > MOVEMENT_EPSILON_SQUARED ? movement.normalize() : Vec3.ZERO;
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

}
