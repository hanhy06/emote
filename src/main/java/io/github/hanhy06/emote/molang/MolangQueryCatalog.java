package io.github.hanhy06.emote.molang;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class MolangQueryCatalog {
    private static final Set<String> SUPPORTED_VALUES = Set.of(
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
        "item_max_use_duration",
        "is_item_equipped"
    );
    private static final Map<String, QuerySignature> SUPPORTED_FUNCTIONS = Map.of(
        "all", QuerySignature.atLeast(2),
        "any", QuerySignature.atLeast(2),
        "approx_eq", QuerySignature.atLeast(2),
        "in_range", QuerySignature.exact(3),
        "is_item_equipped", QuerySignature.range(0, 1),
        "is_item_name_any", QuerySignature.atLeast(2),
        "item_is_charged", QuerySignature.range(0, 1)
    );
    public static final Set<String> SUPPORTED_NAMES = Stream.concat(SUPPORTED_VALUES.stream(), SUPPORTED_FUNCTIONS.keySet().stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private MolangQueryCatalog() {
    }

    public static void validate(MolangEngine.CompiledExpression expression, String path) {
        for (MolangEngine.QueryUse use : expression.queryUses()) {
            if (use.kind() == MolangEngine.QueryUseKind.ASSIGNMENT) {
                throw new IllegalArgumentException(path + " must not assign queries");
            }
            QuerySignature function = SUPPORTED_FUNCTIONS.get(use.name());
            if (use.kind() == MolangEngine.QueryUseKind.VALUE) {
                if (SUPPORTED_VALUES.contains(use.name())) {
                    continue;
                }
                if (function != null) {
                    throw new IllegalArgumentException(path + " must call query function " + use.name());
                }
                throw new IllegalArgumentException(path + " references unsupported query " + use.name());
            }
            if (function == null) {
                if (SUPPORTED_VALUES.contains(use.name())) {
                    throw new IllegalArgumentException(path + " must not call query value " + use.name());
                }
                throw new IllegalArgumentException(path + " references unsupported query " + use.name());
            }
            if (!function.accepts(use.argumentCount())) {
                throw new IllegalArgumentException(
                    path + " calls query " + use.name() + " with " + use.argumentCount() + " arguments; expected " + function.describe()
                );
            }
        }
    }

    private record QuerySignature(int minimumArguments, int maximumArguments) {
        private QuerySignature {
            if (minimumArguments < 0 || maximumArguments < minimumArguments) {
                throw new IllegalArgumentException("invalid query argument range");
            }
        }

        private static QuerySignature exact(int arguments) {
            return new QuerySignature(arguments, arguments);
        }

        private static QuerySignature atLeast(int minimumArguments) {
            return new QuerySignature(minimumArguments, Integer.MAX_VALUE);
        }

        private static QuerySignature range(int minimumArguments, int maximumArguments) {
            return new QuerySignature(minimumArguments, maximumArguments);
        }

        boolean accepts(int argumentCount) {
            return argumentCount >= this.minimumArguments && argumentCount <= this.maximumArguments;
        }

        String describe() {
            if (this.maximumArguments == Integer.MAX_VALUE) {
                return "at least " + this.minimumArguments;
            }
            return this.minimumArguments == this.maximumArguments
                ? Integer.toString(this.minimumArguments)
                : this.minimumArguments + ".." + this.maximumArguments;
        }
    }
}
