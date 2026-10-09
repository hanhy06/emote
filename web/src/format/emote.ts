import { isResourceLocation } from "./resourceLocation";
import { requireArray, requireRecord, requireString } from "./runtimeValue";
import type { EmoteCallback } from "../domain/emoteDefinition";

export const EMOTE_SCHEMA_VERSION = 5;

export function parseCallbacks(value: unknown): EmoteCallback[] {
  if (value === undefined) return [];
  return requireArray(value, "callbacks").map((value, index) => {
    const path = `callbacks[${index}]`;
    const callback = requireRecord(value, path);
    const name = requireString(callback.name, `${path}.name`);
    if (!isResourceLocation(name)) throw new Error(`${path}.name must be a Minecraft resource location.`);
    const payload = callback.payload === undefined ? "" : requireString(callback.payload, `${path}.payload`);
    return { name, payload };
  });
}
