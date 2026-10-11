import { compileAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { EMOTE_SCHEMA_VERSION } from "../format/emote";
import type { ExportResult } from "./types";
import { isSequenceControlId, type SequenceWeightedChoice, type SequenceStep } from "../domain/emoteDefinition";

export async function exportAnimation(document: ConversionDocument, animationIndex: number): Promise<ExportResult[]> {
  const compiled = compileAnimation(document, animationIndex);
  const files: ExportResult[] = [{
    blob: new Blob([JSON.stringify(compiled.animation)], { type: "application/json" }),
    fileName: animationFileNames(document.animations.map((entry) => entry.id))[animationIndex],
  }];
  if (compiled.generatedResourceReferences.size > 0) {
    const { exportResourceBundle } = await import("./resourceBundleExporter");
    files.push(exportResourceBundle(document, compiled.generatedResourceReferences));
  }
  return files;
}

export async function exportAnimations(document: ConversionDocument, includeSequence: boolean): Promise<ExportResult[]> {
  if (document.animations.length === 0) throw new Error("The project does not contain animations.");
  const compiled = document.animations.map((_, index) => compileAnimation(
    document,
    index,
    includeSequence ? false : undefined,
  ));
  const animations = compiled.map((entry) => entry.animation);
  const fileNames = animationFileNames(animations.map((animation) => animation.id));
  const generatedResourceReferences = new Set(compiled.flatMap((entry) => [...entry.generatedResourceReferences]));
  const files: ExportResult[] = animations.map((animation, index) => {
    return {
      blob: new Blob([JSON.stringify(animation)], { type: "application/json" }),
      fileName: fileNames[index],
    };
  });
  if (includeSequence) {
    const sequenceOutput = structuredClone(document.sequence);
    const outputIdBySourceId = new Map(document.animations.flatMap((entry, index) => entry.sourceReferenceId
      ? [[entry.sourceReferenceId, animations[index].id] as const] : []));
    const baseSequenceId = `${sanitizeNamespace(sequenceOutput.namespace)}:${sanitizeResourcePath(sequenceOutput.idPath ?? sequenceOutput.name)}`;
    let sequenceId = baseSequenceId;
    let suffix = 1;
    while (animations.some((animation) => animation.id === sequenceId)) sequenceId = `${baseSequenceId}.${suffix++}`;
    const sequence = {
      type: "sequence",
      schema_version: EMOTE_SCHEMA_VERSION,
      target_minecraft_version: document.targetMinecraftVersion,
      id: sequenceId,
      ...(sequenceOutput.callbacks?.length ? { callbacks: sequenceOutput.callbacks } : {}),
      metadata: { ...sequenceOutput.additionalMetadata, name: sequenceOutput.name, description: sequenceOutput.description },
      settings: { cooldown: sequenceOutput.cooldown, player: sequenceOutput.player },
      steps: sequenceOutput.steps
        ? sequenceOutput.steps.map((step) => remapSequenceStep(step, outputIdBySourceId))
        : animations.map((animation) => ({ emote: animation.id })),
    };
    const baseFileName = emoteFileName(sequence.id);
    let fileName = baseFileName;
    suffix = 1;
    while (fileNames.includes(fileName)) fileName = `${baseFileName.slice(0, -5)}.${suffix++}.json`;
    files.push({
      blob: new Blob([`${JSON.stringify(sequence, null, 2)}\n`], { type: "application/json" }),
      fileName,
    });
  }
  if (generatedResourceReferences.size > 0) {
    const { exportResourceBundle } = await import("./resourceBundleExporter");
    files.push(exportResourceBundle(document, generatedResourceReferences));
  }
  return files;
}

function animationFileNames(animationIds: readonly string[]): string[] {
  const baseNames = animationIds.map(emoteFileName);
  const reservedNames = new Set(baseNames);
  const usedNames = new Set<string>();
  return baseNames.map((baseName) => {
    let fileName = baseName;
    let suffix = 1;
    if (usedNames.has(fileName)) {
      do {
        fileName = `${baseName.slice(0, -5)}.${suffix++}.json`;
      } while (reservedNames.has(fileName) || usedNames.has(fileName));
    }
    usedNames.add(fileName);
    return fileName;
  });
}

function remapSequenceStep(step: SequenceStep, outputIdBySourceId: ReadonlyMap<string, string>): Record<string, unknown> {
  if ("wait" in step) return { wait: step.wait };
  const emote = Array.isArray(step.emote)
    ? flattenSequenceChoices(step.emote, outputIdBySourceId)
    : remapAnimationId(step.emote, outputIdBySourceId);
  return {
    emote,
    ...(step.repeat === undefined ? {} : { repeat: step.repeat }),
    ...(step.transition === undefined ? {} : { transition: step.transition }),
  };
}

function flattenSequenceChoices(choices: readonly SequenceWeightedChoice[], outputIdBySourceId: ReadonlyMap<string, string>): unknown[] {
  const weighted = choices.some((choice) => choice.chance !== undefined);
  return choices.flatMap((choice) => weighted
    ? [remapAnimationId(choice.id, outputIdBySourceId), choice.chance]
    : [remapAnimationId(choice.id, outputIdBySourceId)]);
}

function remapAnimationId(sourceId: string, outputIdBySourceId: ReadonlyMap<string, string>): string {
  if (isSequenceControlId(sourceId)) return sourceId;
  return outputIdBySourceId.get(sourceId) ?? sourceId;
}

export function emoteFileName(id: string): string {
  return `${id.replaceAll(/[:/]/g, ".")}.json`;
}
