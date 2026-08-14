import { strToU8, zipSync } from "fflate";
import { compileConversionAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { formatMinecraftTime, parseMinecraftTime } from "../format/minecraftTime";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeEmoteAnimation } from "../format/serializer";
import { validateResourceVersion } from "./generatedResources";
import type { ExportResult } from "./types";

export function exportDocumentAnimation(document: ConversionDocument, animationIndex: number): ExportResult {
  validateResourceVersion(document, document.targetMinecraftVersion);
  const animation = compileConversionAnimation(document, animationIndex);
  const displayName = document.animations[animationIndex]?.output.displayName ?? "emote";
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeAnimationFileName(displayName)}.json`,
  };
}

export function exportDocumentAnimationBundle(document: ConversionDocument, includeSequence: boolean): ExportResult {
  validateResourceVersion(document, document.targetMinecraftVersion);
  const firstEntry = document.animations[0];
  if (!firstEntry) throw new Error("The project does not contain animations.");
  const animations = document.animations.map((_, index) => compileConversionAnimation(
    document,
    index,
    includeSequence ? { standalone: false } : undefined,
  ));
  const files: Record<string, Uint8Array> = Object.fromEntries(animations.map((animation, index) => [
    `emote.${index + 1}.${sanitizeAnimationFileName(document.animations[index].output.displayName)}.json`,
    strToU8(serializeEmoteAnimation(animation)),
  ]));
  if (includeSequence) {
    const sequenceOutput = document.sequence;
    const sequence = {
      type: "sequence",
      schema_version: 3,
      id: `${sanitizeNamespace(sequenceOutput.namespace)}:${sanitizeResourcePath(sequenceOutput.displayName)}`,
      metadata: { ...sequenceOutput.additionalMetadata, name: sequenceOutput.displayName, description: sequenceOutput.description },
      settings: { cooldown: formatMinecraftTime(parseMinecraftTime(sequenceOutput.cooldown)), player: sequenceOutput.player },
      steps: animations.map((animation) => ({ emote: animation.id })),
    };
    files[`emote.${sanitizeAnimationFileName(sequenceOutput.displayName)}.sequence.json`] = strToU8(`${JSON.stringify(sequence, null, 2)}\n`);
  }
  return {
    blob: new Blob([zipSync(files)], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(includeSequence ? document.sequence.displayName : firstEntry.output.displayName)}${includeSequence ? "" : ".animations"}.zip`,
  };
}

export function sanitizeAnimationFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
