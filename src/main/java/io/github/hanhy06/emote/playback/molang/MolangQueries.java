package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.ToDoubleFunction;

public final class MolangQueries {
    static void applyEmpty(MolangEngine.Session session, Set<String> queries) {
        setSpatialQueries(session, Vec3.ZERO, Vec3.ZERO, queries);
        for (String query : queries) {
            switch (query) {
                case "target_x_rotation", "target_y_rotation", "body_x_rotation", "body_y_rotation",
                     "head_x_rotation", "head_y_rotation", "eye_target_x_rotation", "eye_target_y_rotation",
                     "ground_speed", "vertical_speed", "modified_distance_moved", "walk_distance",
                     "is_moving", "is_on_ground", "is_sneaking", "is_sprinting", "is_swimming", "is_gliding",
                     "is_riding", "is_using_item", "is_sleeping", "is_emoting", "sleep_rotation", "is_on_fire",
                     "is_in_water", "health", "max_health", "is_alive", "is_spectator", "head_is_in_water",
                     "is_in_lava", "is_in_water_or_rain", "hurt_time", "death_ticks", "invulnerable_ticks",
                     "player_level", "item_in_use_duration", "item_remaining_use_duration", "item_max_use_duration",
                     "blocking", "is_eating", "is_jumping", "is_crawling", "is_invisible", "is_levitating",
                     "yaw_speed", "on_fire_time" -> session.setQuery(query, 0.0D);
                default -> {}
            }
        }
        if (queries.contains("is_item_equipped") || queries.contains("item_is_charged") || queries.contains("is_item_name_any")) setItemQueries(
            session,
            queries,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY,
            ItemQueryValue.EMPTY
        );
        if (queries.contains("scoreboard")) setScoreboardQuery(session, objective -> 0.0D);
    }
    static final double MOVEMENT_EPSILON_SQUARED = 1.0E-10D;

    private MolangQueries() {
    }

    static void setScoreboardQuery(MolangEngine.Session session, ToDoubleFunction<String> scoreLookup) {
        session.setQueryFunction("scoreboard", arguments -> MolangEngine.QueryValue.number(scoreLookup.applyAsDouble(arguments.string(0))));
    }

    static void setItemQueries(
        MolangEngine.Session session,
        Set<String> queries,
        ItemQueryValue mainHand,
        ItemQueryValue offHand,
        ItemQueryValue head,
        ItemQueryValue chest,
        ItemQueryValue legs,
        ItemQueryValue feet
    ) {
        ItemQueryValue[] equipment = {mainHand, offHand, head, chest, legs, feet};
        if (queries.contains("is_item_equipped")) session.setQueryFunction("is_item_equipped", mainHand.empty() ? 0.0D : 1.0D, arguments ->
            MolangEngine.QueryValue.bool(!itemInSlot(arguments, 0, equipment).empty())
        );
        if (queries.contains("item_is_charged")) session.setQueryFunction("item_is_charged", mainHand.charged() ? 1.0D : 0.0D, arguments ->
            MolangEngine.QueryValue.bool(itemInSlot(arguments, 0, equipment).charged())
        );
        if (queries.contains("is_item_name_any")) session.setQueryFunction("is_item_name_any", arguments -> {
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

    static void setSpatialQueries(MolangEngine.Session session, Vec3 position, Vec3 movement, Set<String> queries) {
        if (queries.contains("position")) session.setQueryFunction("position", arguments -> MolangEngine.QueryValue.number(axis(position, arguments.number(0))));
        if (queries.contains("position_delta")) session.setQueryFunction("position_delta", arguments -> MolangEngine.QueryValue.number(axis(movement, arguments.number(0))));
        if (queries.contains("movement_direction")) {
            Vec3 direction = movement.lengthSqr() > MOVEMENT_EPSILON_SQUARED ? movement.normalize() : Vec3.ZERO;
            session.setQueryFunction("movement_direction", arguments -> MolangEngine.QueryValue.number(axis(direction, arguments.number(0))));
        }
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
