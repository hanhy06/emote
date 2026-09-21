import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { formatMinecraftTime, parseMinecraftTime } from "../format/time";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeEmoteAnimation } from "../format/serializer";
import { removeRedundantKeyframes } from "../format/keyframeCleanup";
import type { ExportResult } from "./types";
import type { SequenceAnimationStep, SequenceStep } from "../domain/emoteDefinition";
import { ConversionError } from "../foundation/diagnostics";

export function exportDocumentAnimation(document: ConversionDocument, animationIndex: number): ExportResult {
  return compileAnimationFile(document, animationIndex).file;
}

export function exportDocumentAnimationFiles(document: ConversionDocument, includeSequence: boolean): ExportResult[] {
  return compileAnimationFiles(document, includeSequence).files;
}

export async function createDocumentAnimationDownload(document: ConversionDocument, animationIndex: number): Promise<ExportResult[]> {
  const compiled = compileAnimationFile(document, animationIndex);
  const files = [compiled.file];
  if (compiled.generatedResourceReferences.size > 0) {
    const { exportDocumentResourceBundle } = await import("./resourceBundleExporter");
    files.push(exportDocumentResourceBundle(document, compiled.generatedResourceReferences));
  }
  return files;
}

export async function createDocumentAnimationBundleDownload(document: ConversionDocument, includeSequence: boolean): Promise<ExportResult[]> {
  const compiled = compileAnimationFiles(document, includeSequence);
  if (compiled.generatedResourceReferences.size === 0) return compiled.files;
  const { exportDocumentResourceBundle } = await import("./resourceBundleExporter");
  return [...compiled.files, exportDocumentResourceBundle(document, compiled.generatedResourceReferences)];
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
  const compiled = compileConversionAnimationArtifact(document, animationIndex);
  const animation = removeRedundantKeyframes(compiled.animation);
  const displayName = document.animations[animationIndex]?.output.displayName ?? "emote";
  return {
    generatedResourceReferences: compiled.generatedResourceReferences,
    file: {
      blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
      fileName: `emote.${sanitizeAnimationFileName(displayName)}.json`,
    },
  };
}

function compileAnimationFiles(document: ConversionDocument, includeSequence: boolean): CompiledAnimationFiles {
  if (document.animations.length === 0) throw new Error("The project does not contain animations.");
  const compiled = document.animations.map((_, index) => compileConversionAnimationArtifact(
    document,
    index,
    includeSequence ? { standalone: false } : undefined,
  ));
  const animations = compiled.map((entry) => removeRedundantKeyframes(entry.animation));
  const generatedResourceReferences = new Set(compiled.flatMap((entry) => [...entry.generatedResourceReferences]));
  const usedFileNames = new Set<string>();
  const files: ExportResult[] = animations.map((animation, index) => {
    const baseName = sanitizeAnimationFileName(document.animations[index].output.displayName);
    let uniqueName = baseName;
    for (let suffix = 2; usedFileNames.has(uniqueName); suffix++) uniqueName = `${baseName}_${suffix}`;
    usedFileNames.add(uniqueName);
    return {
      blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
      fileName: `emote.${uniqueName}.json`,
    };
  });
  if (includeSequence) {
    const sequenceOutput = document.sequence;
    const outputIdBySourceId = new Map(document.animations.flatMap((entry, index) => entry.source.sourceReferenceId
      ? [[entry.source.sourceReferenceId, animations[index].id] as const] : []));
    const sequenceId = `${sanitizeNamespace(sequenceOutput.namespace)}:${sanitizeResourcePath(sequenceOutput.idPath ?? sequenceOutput.displayName)}`;
    if (animations.some((animation) => animation.id === sequenceId)) {
      throw new ConversionError("duplicate_emote_id", `Animation and sequence normalize to the same id: ${sequenceId}`, sequenceId);
    }
    const sequence = {
      type: "sequence",
      schema_version: 4,
      target_minecraft_version: document.targetMinecraftVersion,
      id: sequenceId,
      metadata: { ...sequenceOutput.additionalMetadata, name: sequenceOutput.displayName, description: sequenceOutput.description },
      settings: { cooldown: formatMinecraftTime(parseMinecraftTime(sequenceOutput.cooldown)), player: sequenceOutput.player },
      steps: sequenceOutput.steps
        ? sequenceOutput.steps.map((step) => remapSequenceStep(step, outputIdBySourceId))
        : animations.map((animation) => ({ emote: animation.id })),
    };
    files.push({
      blob: new Blob([`${JSON.stringify(sequence, null, 2)}\n`], { type: "application/json" }),
      fileName: `emote.${sanitizeAnimationFileName(sequenceOutput.displayName)}.sequence.json`,
    });
  }
  return { generatedResourceReferences, files };
}

function remapSequenceStep(step: SequenceStep, outputIdBySourceId: ReadonlyMap<string, string>): Record<string, unknown> {
  if ("wait" in step) return { wait: step.wait };
  const emote = typeof step.emote === "string"
    ? requireRemappedAnimationId(step.emote, outputIdBySourceId)
    : flattenSequenceChoices(step, outputIdBySourceId);
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
  const outputId = outputIdBySourceId.get(sourceId);
  if (!outputId) throw new ConversionError("missing_sequence_animation", `Sequence references an animation that is not in the document: ${sourceId}`, sourceId);
  return outputId;
}

export function sanitizeAnimationFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
