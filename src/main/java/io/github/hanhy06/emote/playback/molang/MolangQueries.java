package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleFunction;
import java.util.Set;

public final class MolangQueries {
    static void applyEmpty(MolangEngine.Session session, Set<String> queries) {
        setSpatialQueries(session, Vec3.ZERO, Vec3.ZERO, queries);
        if (queries.contains("target_x_rotation")) session.setQuery("target_x_rotation", 0.0D);
        if (queries.contains("target_y_rotation")) session.setQuery("target_y_rotation", 0.0D);
        if (queries.contains("body_x_rotation")) session.setQuery("body_x_rotation", 0.0D);
        if (queries.contains("body_y_rotation")) session.setQuery("body_y_rotation", 0.0D);
        if (queries.contains("head_x_rotation")) session.setQuery("head_x_rotation", 0.0D);
        if (queries.contains("head_y_rotation")) session.setQuery("head_y_rotation", 0.0D);
        if (queries.contains("eye_target_x_rotation")) session.setQuery("eye_target_x_rotation", 0.0D);
        if (queries.contains("eye_target_y_rotation")) session.setQuery("eye_target_y_rotation", 0.0D);
        if (queries.contains("ground_speed")) session.setQuery("ground_speed", 0.0D);
        if (queries.contains("vertical_speed")) session.setQuery("vertical_speed", 0.0D);
        if (queries.contains("modified_distance_moved")) session.setQuery("modified_distance_moved", 0.0D);
        if (queries.contains("walk_distance")) session.setQuery("walk_distance", 0.0D);
        if (queries.contains("is_moving")) session.setQuery("is_moving", 0.0D);
        if (queries.contains("is_on_ground")) session.setQuery("is_on_ground", 0.0D);
        if (queries.contains("is_sneaking")) session.setQuery("is_sneaking", 0.0D);
        if (queries.contains("is_sprinting")) session.setQuery("is_sprinting", 0.0D);
        if (queries.contains("is_swimming")) session.setQuery("is_swimming", 0.0D);
        if (queries.contains("is_gliding")) session.setQuery("is_gliding", 0.0D);
        if (queries.contains("is_riding")) session.setQuery("is_riding", 0.0D);
        if (queries.contains("is_using_item")) session.setQuery("is_using_item", 0.0D);
        if (queries.contains("is_sleeping")) session.setQuery("is_sleeping", 0.0D);
        if (queries.contains("is_emoting")) session.setQuery("is_emoting", 0.0D);
        if (queries.contains("item_is_charged")) session.setQuery("item_is_charged", 0.0D);
        if (queries.contains("sleep_rotation")) session.setQuery("sleep_rotation", 0.0D);
        if (queries.contains("is_on_fire")) session.setQuery("is_on_fire", 0.0D);
        if (queries.contains("is_in_water")) session.setQuery("is_in_water", 0.0D);
        if (queries.contains("health")) session.setQuery("health", 0.0D);
        if (queries.contains("max_health")) session.setQuery("max_health", 0.0D);
        if (queries.contains("is_alive")) session.setQuery("is_alive", 0.0D);
        if (queries.contains("is_spectator")) session.setQuery("is_spectator", 0.0D);
        if (queries.contains("head_is_in_water")) session.setQuery("head_is_in_water", 0.0D);
        if (queries.contains("is_in_lava")) session.setQuery("is_in_lava", 0.0D);
        if (queries.contains("is_in_water_or_rain")) session.setQuery("is_in_water_or_rain", 0.0D);
        if (queries.contains("hurt_time")) session.setQuery("hurt_time", 0.0D);
        if (queries.contains("death_ticks")) session.setQuery("death_ticks", 0.0D);
        if (queries.contains("invulnerable_ticks")) session.setQuery("invulnerable_ticks", 0.0D);
        if (queries.contains("player_level")) session.setQuery("player_level", 0.0D);
        if (queries.contains("item_in_use_duration")) session.setQuery("item_in_use_duration", 0.0D);
        if (queries.contains("item_remaining_use_duration")) session.setQuery("item_remaining_use_duration", 0.0D);
        if (queries.contains("item_max_use_duration")) session.setQuery("item_max_use_duration", 0.0D);
        if (queries.contains("blocking")) session.setQuery("blocking", 0.0D);
        if (queries.contains("is_eating")) session.setQuery("is_eating", 0.0D);
        if (queries.contains("is_jumping")) session.setQuery("is_jumping", 0.0D);
        if (queries.contains("is_crawling")) session.setQuery("is_crawling", 0.0D);
        if (queries.contains("is_invisible")) session.setQuery("is_invisible", 0.0D);
        if (queries.contains("is_levitating")) session.setQuery("is_levitating", 0.0D);
        if (queries.contains("yaw_speed")) session.setQuery("yaw_speed", 0.0D);
        if (queries.contains("on_fire_time")) session.setQuery("on_fire_time", 0.0D);
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
