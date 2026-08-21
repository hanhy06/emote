const PLAYER_STATE_QUERY_NAMES = [
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
  "is_in_water",
] as const;

export const PREVIEW_PLAYER_STATE_QUERIES: Readonly<Record<string, number>> = Object.fromEntries(
  PLAYER_STATE_QUERY_NAMES.flatMap((name) => [[`q.${name}`, 0], [`query.${name}`, 0]]),
);
