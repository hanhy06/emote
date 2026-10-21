import { parse, printParseErrorCode, type ParseError } from "jsonc-parser";
import type { ImportInput } from "../input";

const inputCaches = new WeakMap<ImportInput, Map<string, unknown>>();

export function cachedInputValue<T>(input: ImportInput, key: string, load: () => T): T {
  let cache = inputCaches.get(input);
  if (!cache) {
    cache = new Map<string, unknown>();
    inputCaches.set(input, cache);
  }
  if (cache.has(key)) return cache.get(key) as T;
  const value = load();
  cache.set(key, value);
  return value;
}

export function parseInputJson(input: ImportInput): unknown {
  return cachedInputValue(input, "json", () => JSON.parse(new TextDecoder().decode(input.bytes)) as unknown);
}

export function parseInputJsonc(input: ImportInput): unknown {
  return cachedInputValue(input, "jsonc", () => {
    const errors: ParseError[] = [];
    const value = parse(new TextDecoder().decode(input.bytes), errors, {
      allowTrailingComma: true,
      disallowComments: false,
    }) as unknown;
    const error = errors[0];
    if (error) throw new Error(`Invalid JSONC at offset ${error.offset}: ${printParseErrorCode(error.error)}.`);
    return value;
  });
}
