import { ConversionError } from "../foundation/diagnostics";

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

export function requireNumberArray(value: unknown, path: string): number[] {
  return requireArray(value, path).map((entry, index) => requireNumber(entry, `${path}[${index}]`));
}

function invalidInput(path: string, message: string): ConversionError {
  return new ConversionError("invalid_input", `${path} ${message}.`, path);
}
