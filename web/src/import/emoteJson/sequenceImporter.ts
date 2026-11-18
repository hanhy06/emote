import { parseMinecraftTime } from "../../format/time";
import type { ConversionSequence, ImportedConversionDocument } from "../../domain/conversionDocument";
import type { SequenceStep, SequenceWeightedChoice } from "../../domain/emoteDefinition";
import { isRecord, requireArray, requireRecord, type RuntimeRecord } from "../../format/runtimeValue";
import type { ImportInput } from "../input";
import { parseInputJson } from "../common/inputCache";

interface EmoteSequence extends Pick<ConversionSequence, "id" | "metadata" | "settings" | "callbacks"> {
  target_minecraft_version?: string;
  animation?: Pick<ConversionSequence["clip"], "duration" | "playback" | "events">;
  steps: RuntimeRecord[];
}

export function importSequence(input: ImportInput, formatLabel: string): ImportedConversionDocument {
  const inputValue = parseInputJson(input);
  if (!isRecord(inputValue) || inputValue.type !== "sequence") throw new Error("Input is not an Emote sequence.");
  const { id, metadata, settings, callbacks, animation, steps, target_minecraft_version } = structuredClone(inputValue) as unknown as EmoteSequence;
  requireRecord(settings, "$.settings");
  requireRecord(settings.player, "$.settings.player");
  if (callbacks != null) requireArray(callbacks, "$.callbacks");
  if (animation?.duration !== undefined) parseMinecraftTime(animation.duration, 1);
  for (const event of animation?.events?.timeline ?? []) parseMinecraftTime(event.time);
  return {
    origin: { source: "emote_sequence", sourceName: input.name, formatLabel, metadata, namespace: id.split(":")[0] },
    targetMinecraftVersion: target_minecraft_version,
    nodes: {}, skinCandidates: {}, diagnostics: [], resources: new Map(),
    entries: [{
      type: "sequence",
      id, metadata, settings, callbacks: callbacks ?? undefined,
      nodeIds: [],
      clip: { ...animation, tracks: [] },
      steps: steps.map(importStep),
      sourceName: metadata.name,
      sourcePlayback: structuredClone(animation?.playback),
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
