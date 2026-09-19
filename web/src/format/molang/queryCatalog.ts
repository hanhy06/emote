import catalog from "../../../../shared/molang-queries.json";

export interface MolangQuerySignature {
  minimumArguments: number;
  maximumArguments?: number;
}

export const MOD_SUPPORTED_QUERY_VALUE_NAMES = catalog.values;
export const MOD_SUPPORTED_QUERY_FUNCTION_NAMES = Object.keys(catalog.functions);

export const MOLANG_QUERY_VALUE_NAMES = new Set<string>(catalog.values);
export const MOLANG_QUERY_FUNCTIONS = new Map<string, MolangQuerySignature>(Object.entries(catalog.functions));
export const BAKEABLE_TIME_QUERY_VALUE_NAMES = new Set<string>(catalog.preview.bakeableValues);
export const BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(catalog.preview.builtInFunctions);
export const ZERO_PREVIEW_QUERY_FUNCTION_NAMES = new Set<string>(
  MOD_SUPPORTED_QUERY_FUNCTION_NAMES.filter((name) => !BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES.has(name)),
);
export const PLAYER_ROTATION_QUERY_VALUE_NAMES = new Set<string>(catalog.playerRotationValues);
export const TRUTHY_PREVIEW_QUERY_VALUE_NAMES = new Set<string>(catalog.preview.truthyValues);
