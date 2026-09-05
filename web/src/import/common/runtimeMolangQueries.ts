export const MOD_SUPPORTED_QUERY_VALUE_NAMES = [
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
  "is_item_equipped",
  "blocking",
  "is_eating",
  "is_jumping",
  "is_crawling",
  "is_invisible",
  "is_levitating",
  "yaw_speed",
  "on_fire_time",
] as const;

export const MOD_SUPPORTED_QUERY_FUNCTION_NAMES = [
  "all",
  "any",
  "approx_eq",
  "in_range",
  "is_item_equipped",
  "is_item_name_any",
  "item_is_charged",
  "position",
  "position_delta",
  "movement_direction",
  "scoreboard",
] as const;

const BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(["all", "any", "approx_eq", "in_range"]);
const ZERO_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(
  MOD_SUPPORTED_QUERY_FUNCTION_NAMES.filter((name) => !BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES.has(name)),
);

export const PREVIEW_RUNTIME_QUERY_VALUES: Readonly<Record<string, number>> = Object.fromEntries(
  MOD_SUPPORTED_QUERY_VALUE_NAMES.flatMap((name) => {
    const value = name === "is_on_ground" || name === "is_emoting" ? 1 : 0;
    return [[`q.${name}`, value], [`query.${name}`, value]];
  }),
);

export function previewRuntimeQueryFunction(key: string): number | undefined {
  const name = key.replace(/^(?:q|query)\./, "");
  return ZERO_PREVIEW_QUERY_FUNCTION_NAMES.has(name) ? 0 : undefined;
}
