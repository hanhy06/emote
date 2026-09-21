import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import type { ImportedSequence, SequenceStep, SequenceWeightedChoice } from "../../domain/emoteDefinition";
import type { EmoteMetadata, EmotePlayerBehavior } from "../../format/emoteAnimation";
import { parseInputJson, probeParsedInput } from "../common/inputCache";
import { isRecord } from "../../format/runtimeValue";
import { convertSequenceInput } from "./sequenceJsonConverter";

export const sequenceJsonAdapter: ImportAdapter<ImportedSequence> = {
  id: "emote_sequence",
  label: "Emote sequence JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    return probeParsedInput(input, parseInputJson, (value) => isRecord(value) && value.type === "sequence"
        ? { confidence: 100, reason: "matches an Emote sequence" }
        : { confidence: 0, reason: "not an Emote sequence" }, "not JSON");
  },

  async import(input: ImportInput) {
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
    };
  },
};

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
