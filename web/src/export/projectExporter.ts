import { compileAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { EMOTE_SCHEMA_VERSION } from "../format/emote";
import type { ExportResult } from "./types";
import { isSequenceControlId, type SequenceAnimationStep, type SequenceStep } from "../domain/emoteDefinition";

export async function exportAnimation(document: ConversionDocument, animationIndex: number): Promise<ExportResult[]> {
  const compiled = compileAnimationFile(document, animationIndex);
  const files = [compiled.file];
  if (compiled.generatedResourceReferences.size > 0) {
    const { exportResourceBundle } = await import("./resourceBundleExporter");
    files.push(exportResourceBundle(document, compiled.generatedResourceReferences));
  }
  return files;
}

export async function exportAnimations(document: ConversionDocument, includeSequence: boolean): Promise<ExportResult[]> {
  const compiled = compileAnimationFiles(document, includeSequence);
  if (compiled.generatedResourceReferences.size === 0) return compiled.files;
  const { exportResourceBundle } = await import("./resourceBundleExporter");
  return [...compiled.files, exportResourceBundle(document, compiled.generatedResourceReferences)];
}

interface CompiledAnimationFile {
  generatedResourceReferences: ReadonlySet<string>;
  file: ExportResult;
}

interface CompiledAnimationFiles {
  generatedResourceReferences: ReadonlySet<string>;
  files: ExportResult[];
}

function compileAnimationFile(document: ConversionDocument, animationIndex: number): CompiledAnimationFile {
  const compiled = compileAnimation(document, animationIndex);
  const animation = compiled.animation;
  return {
    generatedResourceReferences: compiled.generatedResourceReferences,
    file: {
      blob: new Blob([JSON.stringify(animation)], { type: "application/json" }),
      fileName: animationFileNames(document.animations.map((entry) => entry.id))[animationIndex],
    },
  };
}

function compileAnimationFiles(document: ConversionDocument, includeSequence: boolean): CompiledAnimationFiles {
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
      ...(sequenceOutput.callbacks?.length ? { callbacks: sequenceOutput.callbacks.map((callback) => ({ ...callback })) } : {}),
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
  return { generatedResourceReferences, files };
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
    ? flattenSequenceChoices(step, outputIdBySourceId)
    : requireRemappedAnimationId(step.emote, outputIdBySourceId);
  return {
    emote,
    ...(step.repeat === undefined ? {} : { repeat: step.repeat }),
    ...(step.transition === undefined ? {} : { transition: step.transition }),
  };
}

function flattenSequenceChoices(step: SequenceAnimationStep, outputIdBySourceId: ReadonlyMap<string, string>): unknown[] {
  const choices = step.emote as Exclude<SequenceAnimationStep["emote"], string>;
  const weighted = choices.some((choice) => choice.chance !== undefined);
  return choices.flatMap((choice) => weighted
    ? [requireRemappedAnimationId(choice.id, outputIdBySourceId), choice.chance]
    : [requireRemappedAnimationId(choice.id, outputIdBySourceId)]);
}

function requireRemappedAnimationId(sourceId: string, outputIdBySourceId: ReadonlyMap<string, string>): string {
  if (isSequenceControlId(sourceId)) return sourceId;
  const outputId = outputIdBySourceId.get(sourceId);
  return outputId ?? sourceId;
}

export function emoteFileName(id: string): string {
  return `${id.replaceAll(/[:/]/g, ".")}.json`;
}
