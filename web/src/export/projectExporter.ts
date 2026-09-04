import { compileConversionAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { formatMinecraftTime, parseMinecraftTime } from "../format/time";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeEmoteAnimation } from "../format/serializer";
import { animationUsesGeneratedResources } from "./generatedResources";
import type { EmoteAnimation } from "../format/emoteAnimation";
import type { ExportResult } from "./types";

export function exportDocumentAnimation(document: ConversionDocument, animationIndex: number): ExportResult {
  return compileAnimationFile(document, animationIndex).file;
}

export function exportDocumentAnimationFiles(document: ConversionDocument, includeSequence: boolean): ExportResult[] {
  return compileAnimationFiles(document, includeSequence).files;
}

export async function createDocumentAnimationDownload(document: ConversionDocument, animationIndex: number): Promise<ExportResult[]> {
  const compiled = compileAnimationFile(document, animationIndex);
  const files = [compiled.file];
  if (animationUsesGeneratedResources(compiled.animation, document.resources)) {
    const { exportDocumentResourceBundle } = await import("./resourceBundleExporter");
    files.push(exportDocumentResourceBundle(document));
  }
  return files;
}

export async function createDocumentAnimationBundleDownload(document: ConversionDocument, includeSequence: boolean): Promise<ExportResult[]> {
  const compiled = compileAnimationFiles(document, includeSequence);
  if (compiled.animations.some((animation) => animationUsesGeneratedResources(animation, document.resources))) {
    const { exportDocumentResourceBundle } = await import("./resourceBundleExporter");
    compiled.files.push(exportDocumentResourceBundle(document));
  }
  return compiled.files;
}

interface CompiledAnimationFile {
  animation: EmoteAnimation;
  file: ExportResult;
}

interface CompiledAnimationFiles {
  animations: EmoteAnimation[];
  files: ExportResult[];
}

function compileAnimationFile(document: ConversionDocument, animationIndex: number): CompiledAnimationFile {
  const animation = compileConversionAnimation(document, animationIndex);
  const displayName = document.animations[animationIndex]?.output.displayName ?? "emote";
  return {
    animation,
    file: {
      blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
      fileName: `emote.${sanitizeAnimationFileName(displayName)}.json`,
    },
  };
}

function compileAnimationFiles(document: ConversionDocument, includeSequence: boolean): CompiledAnimationFiles {
  if (document.animations.length === 0) throw new Error("The project does not contain animations.");
  const animations = document.animations.map((_, index) => compileConversionAnimation(
    document,
    index,
    includeSequence ? { standalone: false } : undefined,
  ));
  const files: ExportResult[] = animations.map((animation, index) => ({
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${index + 1}.${sanitizeAnimationFileName(document.animations[index].output.displayName)}.json`,
  }));
  if (includeSequence) {
    const sequenceOutput = document.sequence;
    const sequence = {
      type: "sequence",
      schema_version: 4,
      target_minecraft_version: document.targetMinecraftVersion,
      id: `${sanitizeNamespace(sequenceOutput.namespace)}:${sanitizeResourcePath(sequenceOutput.displayName)}`,
      metadata: { ...sequenceOutput.additionalMetadata, name: sequenceOutput.displayName, description: sequenceOutput.description },
      settings: { cooldown: formatMinecraftTime(parseMinecraftTime(sequenceOutput.cooldown)), player: sequenceOutput.player },
      steps: animations.map((animation) => ({ emote: animation.id })),
    };
    files.push({
      blob: new Blob([`${JSON.stringify(sequence, null, 2)}\n`], { type: "application/json" }),
      fileName: `emote.${sanitizeAnimationFileName(sequenceOutput.displayName)}.sequence.json`,
    });
  }
  return { animations, files };
}

export function documentAnimationUsesGeneratedResources(document: ConversionDocument, animationIndex: number): boolean {
  return animationUsesGeneratedResources(compileConversionAnimation(document, animationIndex), document.resources);
}

export function sanitizeAnimationFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
