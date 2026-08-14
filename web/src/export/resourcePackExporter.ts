import { strToU8, zipSync } from "fflate";
import { compileConversionAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import { generatedResourceFiles } from "./generatedResources";
import { sanitizeAnimationFileName } from "./projectExporter";
import type { ExportResult } from "./types";

export function exportDocumentResourcePack(document: ConversionDocument, animationIndex: number): ExportResult {
  const generatedResources = generatedResourceFiles(document, document.targetMinecraftVersion);
  const animation = compileConversionAnimation(document, animationIndex);
  const packFormat = resourcePackFormat(document.resourceMinecraftVersion ?? document.targetMinecraftVersion);
  const files: Record<string, Uint8Array> = {
    "pack.mcmeta": strToU8(`${JSON.stringify({
      pack: {
        description: `${animation.metadata.name} emote resources`,
        min_format: packFormat,
        max_format: packFormat,
      },
    }, null, 2)}\n`),
  };
  for (const [path, data] of generatedResources) files[path] = data;
  return {
    blob: new Blob([zipSync(files, { level: 9 })], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(document.animations[animationIndex]?.output.displayName ?? "emote")}.zip`,
  };
}

function resourcePackFormat(minecraftVersion: string): [number, number] {
  if (minecraftVersion === "26.2") return [88, 0];
  throw new Error(`Resource pack metadata is not configured for Minecraft ${minecraftVersion}.`);
}
