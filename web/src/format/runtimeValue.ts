import { ConversionError } from "../import/errors";

export type RuntimeRecord = Record<string, unknown>;

export function isRecord(value: unknown): value is RuntimeRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function requireRecord(value: unknown, path: string): RuntimeRecord {
  if (!isRecord(value)) throw invalidInput(path, "must be an object");
  return value;
}

export function requireArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) throw invalidInput(path, "must be an array");
  return value;
}

export function requireString(value: unknown, path: string): string {
  if (typeof value !== "string") throw invalidInput(path, "must be a string");
  return value;
}

export function requireNumber(value: unknown, path: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) throw invalidInput(path, "must be a finite number");
  return value;
}

export function requireBoolean(value: unknown, path: string): boolean {
  if (typeof value !== "boolean") throw invalidInput(path, "must be a boolean");
  return value;
}

export function optionalRecord(value: unknown, path: string): RuntimeRecord | undefined {
  return value === undefined ? undefined : requireRecord(value, path);
}

export function optionalArray(value: unknown, path: string): unknown[] | undefined {
  return value === undefined ? undefined : requireArray(value, path);
}

export function optionalString(value: unknown, path: string): string | undefined {
  return value === undefined ? undefined : requireString(value, path);
}

export function optionalNumber(value: unknown, path: string): number | undefined {
  return value === undefined ? undefined : requireNumber(value, path);
}

export function optionalBoolean(value: unknown, path: string): boolean | undefined {
  return value === undefined ? undefined : requireBoolean(value, path);
}

export function requireStringValue<const T extends string>(value: unknown, allowed: readonly T[], path: string): T {
  const string = requireString(value, path);
  if (!allowed.includes(string as T)) throw invalidInput(path, `must be one of: ${allowed.join(", ")}`);
  return string as T;
}

export function requireNumberArray(value: unknown, path: string): number[] {
  return requireArray(value, path).map((entry, index) => requireNumber(entry, `${path}[${index}]`));
}

export function requireStringArray(value: unknown, path: string): string[] {
  return requireArray(value, path).map((entry, index) => requireString(entry, `${path}[${index}]`));
}

function invalidInput(path: string, message: string): ConversionError {
  return new ConversionError("invalid_input", `${path} ${message}.`, path);
}
