import { EMOTE_SCHEMA_VERSION, parseCallbacks } from "../../format/emote";
import type { EmoteCallback, EmoteMetadata, EmotePlayerBehavior, ImportedSequence, SequenceStep, SequenceWeightedChoice } from "../../domain/emoteDefinition";
import { parseMinecraftTime } from "../../format/time";
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
import type { ImportInput } from "../input";
import { ConversionError } from "../../foundation/diagnostics";
import { parseInputJson } from "../common/inputCache";

interface EmoteSequence {
  callbacks?: EmoteCallback[];
  type: "sequence";
  schema_version: typeof EMOTE_SCHEMA_VERSION;
  target_minecraft_version?: string;
  id: string;
  metadata: RuntimeRecord;
  settings: {
    cooldown: string;
    player: RuntimeRecord;
  };
  steps: RuntimeRecord[];
}

function convertSequenceInput(input: ImportInput): EmoteSequence | null {
  let value: unknown;
  try {
    value = parseInputJson(input);
  } catch {
    return null;
  }
  if (!isRecord(value) || value.type !== "sequence") return null;
  if (value.schema_version === EMOTE_SCHEMA_VERSION) return requireSequence(value);
  throw new ConversionError("unsupported_sequence_schema", `Unsupported sequence schema: ${String(value.schema_version)}.`, "schema_version");
}

export function importSequence(input: ImportInput): ImportedSequence {
  const sequence = convertSequenceInput(input);
  if (!sequence) throw new Error("Input is not an Emote sequence.");
  return {
    kind: "sequence",
    source: "emote_sequence",
    sourceName: input.name,
    id: sequence.id,
    ...(sequence.target_minecraft_version ? { targetMinecraftVersion: sequence.target_minecraft_version } : {}),
    metadata: { ...sequence.metadata } as EmoteMetadata,
    cooldown: sequence.settings.cooldown,
    player: { ...sequence.settings.player, stop_conditions: { ...(sequence.settings.player.stop_conditions as Record<string, unknown>) } } as EmotePlayerBehavior,
    steps: sequence.steps.map(importStep),
    callbacks: sequence.callbacks?.map((callback) => ({ ...callback })),
  };
}

function importStep(step: Record<string, unknown>): SequenceStep {
  if (typeof step.wait === "string") return { wait: step.wait };
  const emote = typeof step.emote === "string" ? step.emote : importChoices(step.emote as unknown[]);
  return {
    emote,
    ...(typeof step.repeat === "number" ? { repeat: step.repeat } : {}),
    ...(typeof step.transition === "string" ? { transition: step.transition } : {}),
  };
}

function importChoices(values: unknown[]): SequenceWeightedChoice[] {
  const weighted = values.length > 1 && typeof values[1] === "number";
  const result: SequenceWeightedChoice[] = [];
  for (let index = 0; index < values.length; index += weighted ? 2 : 1) {
    result.push({ id: values[index] as string, ...(weighted ? { chance: values[index + 1] as number } : {}) });
  }
  return result;
}

function requireSequence(value: unknown): EmoteSequence {
  const root = requireRecord(value, "sequence");
  if (root.type !== "sequence") throw invalid("type", "must be sequence");
  if (root.schema_version !== EMOTE_SCHEMA_VERSION) throw invalid("schema_version", `must be ${EMOTE_SCHEMA_VERSION}`);
  if (root.participants !== undefined && root.participants !== null) throw invalid("participants", "two-player matching is no longer supported");
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
  return {
    type: "sequence", schema_version: EMOTE_SCHEMA_VERSION,
    ...(typeof root.target_minecraft_version === "string" ? { target_minecraft_version: root.target_minecraft_version } : {}),
    id, metadata, settings: { cooldown, player }, steps,
    ...(root.callbacks === undefined ? {} : { callbacks: parseCallbacks(root.callbacks) }),
  };
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
  if (step.await_partner !== undefined) throw invalid(path + ".await_partner", "two-player matching is no longer supported");
  const hasEmote = step.emote !== undefined && step.emote !== null;
  const hasWait = step.wait !== undefined && step.wait !== null;
  if (hasEmote === hasWait) throw invalid(path, "must contain exactly one of emote or wait");
  if (hasWait) {
    if (step.repeat !== undefined) throw invalid(`${path}.repeat`, "is not supported on a wait step");
    if (step.transition !== undefined) throw invalid(`${path}.transition`, "is supported only on an emote step");
    const wait = requireString(step.wait, `${path}.wait`);
    parseTime(wait, 1, `${path}.wait`);
    return { wait };
  }

  const emote = requireEmoteChoice(step.emote, `${path}.emote`);
  const result: RuntimeRecord = { emote };
  if (step.repeat !== undefined) {
    const repeat = requireNumber(step.repeat, `${path}.repeat`);
    if (!Number.isInteger(repeat) || repeat < 1) throw invalid(`${path}.repeat`, "must be a positive integer");
    result.repeat = repeat;
  }
  if (step.transition !== undefined) {
    const transition = requireString(step.transition, `${path}.transition`);
    parseTime(transition, 0, `${path}.transition`);
    result.transition = transition;
  }
  return result;
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
