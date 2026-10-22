import catalog from "../../../../shared/molang-queries.json";

export const MOLANG_QUERY_VALUE_NAMES = new Set<string>(catalog.values);
export const PLAYER_ROTATION_QUERY_VALUE_NAMES = new Set<string>(catalog.playerRotationValues);
export const BAKEABLE_TIME_QUERY_VALUE_NAMES = new Set<string>(catalog.preview.bakeableValues);
export const BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(catalog.preview.builtInFunctions);
export const ZERO_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(
  Object.keys(catalog.functions).filter((name) => !BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES.has(name)),
);
export const TRUTHY_PREVIEW_QUERY_VALUE_NAMES = new Set<string>(catalog.preview.truthyValues);
