import { compileAnimation } from "../compiler/animationCompiler";
import { animationEntries, type ConversionDocument } from "../domain/conversionDocument";
import { isResourceLocation, sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { EMOTE_SCHEMA_VERSION } from "../format/emote";
import { ConversionError } from "../foundation/diagnostics";
import type { ExportResult } from "./types";
import { animationReferenceTargets, createDefaultPlayerBehavior, isSequenceControlId, type SequenceWeightedChoice, type SequenceStep } from "../domain/emoteDefinition";

export type ExportSelection = "animations" | "sequence" | number;

export async function exportProject(document: ConversionDocument, selection: ExportSelection): Promise<ExportResult[]> {
  const entries = animationEntries(document);
  const includeSequence = selection === "sequence";
  const importedSequence = includeSequence ? document.entries.find((entry) => entry.type === "sequence") : undefined;
  if (typeof selection !== "number" && !entries.length && !importedSequence) throw new Error("The project does not contain animations.");
  const animationIds = entries.map((entry) => entry.id);
  if (typeof selection !== "number") {
    const seenIds = new Set<string>();
    for (const id of animationIds) {
      if (seenIds.has(id)) {
        throw new ConversionError("duplicate_animation_id", `Multiple animations use the ID ${id}. Give each animation a unique ID before downloading the bundle.`);
      }
      seenIds.add(id);
    }
  }
  const fileNames = animationFileNames(animationIds);
  const generatedResourceReferences = new Set<string>();
  const indexes = typeof selection === "number" ? [selection] : entries.map((_, index) => index);
  const files: ExportResult[] = indexes.map((index) => {
    const entry = entries[index];
    if (!entry) throw new ConversionError("unknown_animation", `Animation ${index + 1} does not exist.`);
    const compiled = compileAnimation(document, entry, includeSequence ? false : undefined);
    for (const reference of compiled.generatedResourceReferences) generatedResourceReferences.add(reference);
    return { fileName: fileNames[index], blob: new Blob([JSON.stringify(compiled.animation)], { type: "application/json" }) };
  });
  if (includeSequence) {
    const references = animationReferenceTargets(entries);
    const metadata = importedSequence?.metadata ?? document.origin.metadata;
    const id = importedSequence?.id ?? `${document.origin.namespace}:${metadata.name}`;
    const separator = id.indexOf(":");
    const baseSequenceId = isResourceLocation(id) ? id
      : `${sanitizeNamespace(id.slice(0, separator))}:${sanitizeResourcePath(id.slice(separator + 1))}`;
    let sequenceId = baseSequenceId;
    let suffix = 1;
    while (entries.some((entry) => entry.id === sequenceId)) sequenceId = `${baseSequenceId}.${suffix++}`;
    const sequence = {
      type: "sequence",
      schema_version: EMOTE_SCHEMA_VERSION,
      target_minecraft_version: document.targetMinecraftVersion,
      id: sequenceId,
      ...(importedSequence?.callbacks?.length ? { callbacks: importedSequence.callbacks } : {}),
      metadata,
      settings: importedSequence?.settings ?? {
        cooldown: entries[0]?.settings?.cooldown ?? "0t",
        player: entries[0]?.settings?.player ?? createDefaultPlayerBehavior(),
      },
      steps: importedSequence
        ? importedSequence.steps.map((step) => remapSequenceStep(step, references))
        : entries.map((entry) => ({ emote: entry.id })),
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

function remapSequenceStep(step: SequenceStep, references: ReadonlyMap<string, readonly string[]>): Record<string, unknown> {
  if ("wait" in step) return { wait: step.wait };
  const emote = Array.isArray(step.emote)
    ? flattenSequenceChoices(step.emote, references)
    : remapAnimationId(step.emote, references);
  return { ...step, emote };
}

function flattenSequenceChoices(choices: readonly SequenceWeightedChoice[], references: ReadonlyMap<string, readonly string[]>): unknown[] {
  const weighted = choices.some((choice) => choice.chance !== undefined);
  return choices.flatMap((choice) => weighted
    ? [remapAnimationId(choice.id, references), choice.chance]
    : [remapAnimationId(choice.id, references)]);
}

function remapAnimationId(sourceId: string, references: ReadonlyMap<string, readonly string[]>): string {
  if (isSequenceControlId(sourceId)) return sourceId;
  const targets = references.get(sourceId);
  return targets?.length === 1 ? targets[0] : sourceId;
}

function emoteFileName(id: string): string {
  return `${id.replaceAll(/[:/]/g, ".")}.json`;
}
