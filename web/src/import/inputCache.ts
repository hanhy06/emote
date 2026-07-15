import type { ImportInput } from "./adapter";

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

function cacheFor(input: ImportInput): Map<string, unknown> {
  const existing = inputCaches.get(input);
  if (existing) return existing;
  const created = new Map<string, unknown>();
  inputCaches.set(input, created);
  return created;
}
