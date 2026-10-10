import { EMOTE_SCHEMA_VERSION } from "../../format/emote";
import type { EmoteCallback, EmoteMetadata, EmotePlayerBehavior, ImportedSequence, SequenceStep, SequenceWeightedChoice } from "../../domain/emoteDefinition";
import { isRecord, type RuntimeRecord } from "../../format/runtimeValue";
import type { ImportInput } from "../input";
import { parseInputJson } from "../common/inputCache";
import { normalizeSequenceTimes } from "../../domain/animationIRConversion";

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
  return value as unknown as EmoteSequence;
}

export function importSequence(input: ImportInput): ImportedSequence {
  const sequence = convertSequenceInput(input);
  if (!sequence) throw new Error("Input is not an Emote sequence.");
  const imported: ImportedSequence = {
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
  normalizeSequenceTimes(imported);
  return imported;
}

function importStep(step: Record<string, unknown>): SequenceStep {
  if ("wait" in step) return { wait: step.wait as string };
  const emote = Array.isArray(step.emote) ? importChoices(step.emote) : step.emote as string;
  return {
    emote,
    ...(step.repeat !== undefined ? { repeat: step.repeat as number } : {}),
    ...(step.transition !== undefined ? { transition: step.transition as string } : {}),
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
