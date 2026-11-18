import { EMOTE_SCHEMA_VERSION } from "../../format/emote";
import type { ConversionSequence, ImportedConversionDocument } from "../../domain/conversionDocument";
import type { SequenceStep, SequenceWeightedChoice } from "../../domain/emoteDefinition";
import { isRecord, requireRecord, type RuntimeRecord } from "../../format/runtimeValue";
import type { ImportInput } from "../input";
import { parseInputJson } from "../common/inputCache";

interface EmoteSequence extends Pick<ConversionSequence, "id" | "metadata" | "settings" | "callbacks"> {
  type: "sequence";
  schema_version: 4 | typeof EMOTE_SCHEMA_VERSION;
  target_minecraft_version?: string;
  animation?: Pick<ConversionSequence["clip"], "duration" | "playback" | "events">;
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

export function importSequence(input: ImportInput, formatLabel: string): ImportedConversionDocument {
  const sequence = convertSequenceInput(input);
  if (!sequence) throw new Error("Input is not an Emote sequence.");
  requireRecord(sequence.settings, "$.settings");
  requireRecord(sequence.settings.player, "$.settings.player");
  return {
    origin: { source: "emote_sequence", sourceName: input.name, formatLabel, metadata: sequence.metadata, namespace: sequence.id.split(":")[0] },
    targetMinecraftVersion: sequence.target_minecraft_version,
    nodes: {}, skinCandidates: {}, diagnostics: [], resources: new Map(),
    entries: [{
      type: "sequence",
      id: sequence.id,
      metadata: structuredClone(sequence.metadata),
      settings: structuredClone(sequence.settings),
      callbacks: sequence.callbacks?.map((callback) => ({ ...callback })),
      nodeIds: [],
      clip: { ...structuredClone(sequence.animation), tracks: [] },
      steps: sequence.steps.map(importStep),
      sourceName: sequence.metadata.name,
      sourcePlayback: structuredClone(sequence.animation?.playback),
      playbackMode: "source",
    }],
  };
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
