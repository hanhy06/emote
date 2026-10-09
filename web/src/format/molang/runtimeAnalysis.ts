import {
  BAKEABLE_TIME_QUERY_VALUE_NAMES,
  BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES,
  MOLANG_QUERY_VALUE_NAMES,
  TRUTHY_PREVIEW_QUERY_VALUE_NAMES,
  ZERO_PREVIEW_QUERY_FUNCTION_NAMES,
} from "./queryCatalog";
import { nextMolangSourceCharacter, scanMolangSource } from "./sourceScanner";

const QUERY_IDENTIFIER = /^(?:q|query)\.([A-Za-z_][A-Za-z0-9_]*)$/i;

export const PREVIEW_RUNTIME_QUERY_VALUES: Readonly<Record<string, number>> = Object.fromEntries(
  [...MOLANG_QUERY_VALUE_NAMES].flatMap((name) => {
    const value = TRUTHY_PREVIEW_QUERY_VALUE_NAMES.has(name) ? 1 : 0;
    return [[`q.${name}`, value], [`query.${name}`, value]];
  }),
);

export function previewRuntimeQueryFunction(key: string): number | undefined {
  const name = key.replace(/^(?:q|query)\./, "");
  return ZERO_PREVIEW_QUERY_FUNCTION_NAMES.has(name) ? 0 : undefined;
}

export function usesRuntimeMolangState(value: unknown): boolean {
  if (typeof value !== "string") return false;
  for (const token of scanMolangSource(value)) {
    if (token.kind !== "identifier") continue;
    if (/^math\.(?:random|random_integer|die_roll|die_roll_integer)$/i.test(token.value)) return true;
    const match = QUERY_IDENTIFIER.exec(token.value);
    if (!match) continue;
    const name = match[1].toLowerCase();
    if (nextMolangSourceCharacter(value, token.end) === "(") {
      if (!BUILT_IN_PREVIEW_QUERY_FUNCTION_NAMES.has(name)) return true;
    } else if (!BAKEABLE_TIME_QUERY_VALUE_NAMES.has(name)) {
      return true;
    }
  }
  return false;
}
