import { parseMinecraftTime } from "../../format/minecraftTime";
import { isResourceLocation } from "../../format/resourceLocation";
import {
  isRecord,
  requireArray,
  requireBoolean,
  requireNumber,
  requireRecord,
  requireString,
  type RuntimeRecord,
} from "../../format/runtimeValue";
import type { ImportInput } from "../adapter";
import { ConversionError } from "../../foundation/diagnostics";
import { parseInputJson } from "../inputCache";

export interface EmoteSequence {
  type: "sequence";
  schema_version: 4;
  id: string;
  metadata: RuntimeRecord;
  settings: {
    cooldown: string;
    player: RuntimeRecord;
  };
  steps: RuntimeRecord[];
}

export function convertSequenceInput(input: ImportInput): EmoteSequence | null {
  let value: unknown;
  try {
    value = parseInputJson(input);
  } catch {
    return null;
  }
  if (!isRecord(value) || value.type !== "sequence") return null;
  if (value.schema_version === 1) return migrateSchema1Sequence(value);
  if (value.schema_version === 4) return requireSequence(value);
  throw new ConversionError("unsupported_sequence_schema", `Unsupported sequence schema: ${String(value.schema_version)}.`, "schema_version");
}

function migrateSchema1Sequence(root: RuntimeRecord): EmoteSequence {
  return requireSequence({
    type: "sequence",
    schema_version: 4,
    id: root.id,
    metadata: root.metadata,
    settings: { cooldown: "0t", player: root.player },
    steps: root.steps,
  });
}

function requireSequence(value: unknown): EmoteSequence {
  const root = requireRecord(value, "sequence");
  if (root.type !== "sequence") throw invalid("type", "must be sequence");
  if (root.schema_version !== 4) throw invalid("schema_version", "must be 4");
  const id = requireString(root.id, "id");
  if (!isResourceLocation(id)) throw invalid("id", "must be a Minecraft resource location");
  const metadata = requireRecord(root.metadata, "metadata");
  requireString(metadata.name, "metadata.name");
  requireString(metadata.description, "metadata.description");
  const settings = requireRecord(root.settings, "settings");
  const cooldown = requireString(settings.cooldown, "settings.cooldown");
  parseTime(cooldown, 0, "settings.cooldown");
  const player = requireRecord(settings.player, "settings.player");
  requirePlayer(player);
  const steps = requireArray(root.steps, "steps").map(requireStep);
  if (steps.length === 0) throw invalid("steps", "must not be empty");
  steps.forEach((step, index) => {
    if (!("wait" in step)) return;
    if (index === 0 || index === steps.length - 1) throw invalid(`steps[${index}].wait`, "must be between emote steps");
    if ("wait" in steps[index - 1]) throw invalid(`steps[${index}].wait`, "must not follow another wait step");
  });
  return { type: "sequence", schema_version: 4, id, metadata, settings: { cooldown, player }, steps };
}

function requirePlayer(player: RuntimeRecord): void {
  requireBoolean(player.hidden, "settings.player.hidden");
  const stopConditions = requireRecord(player.stop_conditions, "settings.player.stop_conditions");
  const movementDistance = requireNumber(stopConditions.movement_distance, "settings.player.stop_conditions.movement_distance");
  if (movementDistance < 0) throw invalid("settings.player.stop_conditions.movement_distance", "must not be negative");
  for (const key of ["jump", "submerge", "ride", "damage", "attack", "game_mode_change"] as const) {
    requireBoolean(stopConditions[key], `settings.player.stop_conditions.${key}`);
  }
}

function requireStep(value: unknown, index: number): RuntimeRecord {
  const path = `steps[${index}]`;
  const step = requireRecord(value, path);
  const hasEmote = step.emote !== undefined && step.emote !== null;
  const hasWait = step.wait !== undefined && step.wait !== null;
  if (hasEmote === hasWait) throw invalid(path, "must contain exactly one of emote or wait");
  if (hasWait) {
    if (step.repeat !== undefined) throw invalid(`${path}.repeat`, "is not supported on a wait step");
    const wait = requireString(step.wait, `${path}.wait`);
    parseTime(wait, 1, `${path}.wait`);
    return { wait };
  }

  const emote = requireEmoteChoice(step.emote, `${path}.emote`);
  if (step.repeat === undefined) return { emote };
  const repeat = requireNumber(step.repeat, `${path}.repeat`);
  if (!Number.isInteger(repeat) || repeat < 1) throw invalid(`${path}.repeat`, "must be a positive integer");
  return { emote, repeat };
}

function requireEmoteChoice(value: unknown, path: string): string | unknown[] {
  if (typeof value === "string") {
    if (!isResourceLocation(value)) throw invalid(path, "must be a Minecraft resource location");
    return value;
  }
  const choices = requireArray(value, path);
  if (choices.length === 0) throw invalid(path, "must not be empty");
  const weighted = choices.length > 1 && typeof choices[1] === "number";
  if (weighted && choices.length % 2 !== 0) throw invalid(path, "must contain complete id and chance pairs");
  const ids = new Set<string>();
  let totalChance = 0;
  for (let index = 0; index < choices.length; index += weighted ? 2 : 1) {
    const id = requireString(choices[index], `${path}[${index}]`);
    if (!isResourceLocation(id)) throw invalid(`${path}[${index}]`, "must be a Minecraft resource location");
    if (ids.has(id)) throw invalid(`${path}[${index}]`, "must not duplicate an earlier candidate");
    ids.add(id);
    if (weighted) {
      const chance = requireNumber(choices[index + 1], `${path}[${index + 1}]`);
      if (!Number.isInteger(chance) || chance < 1 || chance > 100) {
        throw invalid(`${path}[${index + 1}]`, "must be an integer between 1 and 100");
      }
      totalChance += chance;
    }
  }
  if (weighted && totalChance !== 100) throw invalid(path, "chances must total 100");
  return [...choices];
}

function parseTime(value: string, minimumTicks: number, path: string): void {
  try {
    parseMinecraftTime(value, minimumTicks);
  } catch (reason) {
    throw invalid(path, reason instanceof Error ? reason.message : "must be a Minecraft time");
  }
}

function invalid(path: string, message: string): ConversionError {
  return new ConversionError("invalid_sequence", `${path} ${message}.`, path);
}
