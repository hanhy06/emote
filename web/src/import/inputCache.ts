import { parse, printParseErrorCode, type ParseError } from "jsonc-parser";
import type { ImportInput, ProbeResult } from "./adapter";

const inputCaches = new WeakMap<ImportInput, Map<string, unknown>>();

export function cachedInputValue<T>(input: ImportInput, key: string, load: () => T): T {
  const cache = cacheFor(input);
  if (cache.has(key)) return cache.get(key) as T;
  const value = load();
  cache.set(key, value);
  return value;
}

export function cachedInputPromise<T>(input: ImportInput, key: string, load: () => Promise<T>): Promise<T> {
  const cache = cacheFor(input);
  if (cache.has(key)) return cache.get(key) as Promise<T>;
  const promise = load();
  cache.set(key, promise);
  return promise;
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

export function probeParsedInput(
  input: ImportInput,
  parseInput: (input: ImportInput) => unknown,
  probe: (value: unknown) => ProbeResult,
  invalidReason: string,
): ProbeResult {
  try { return probe(parseInput(input)); } catch { return { confidence: 0, reason: invalidReason }; }
}

function cacheFor(input: ImportInput): Map<string, unknown> {
  const existing = inputCaches.get(input);
  if (existing) return existing;
  const created = new Map<string, unknown>();
  inputCaches.set(input, created);
  return created;
}
