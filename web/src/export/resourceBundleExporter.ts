import { zipSync } from "fflate";
import type { ConversionDocument } from "../domain/conversionDocument";
import { generatedResourceFiles } from "./generatedResources";
import { sanitizeAnimationFileName } from "./projectExporter";
import type { ExportResult } from "./types";

export function exportDocumentResourceBundle(document: ConversionDocument): ExportResult {
  const generatedResources = generatedResourceFiles(document, document.targetMinecraftVersion);
  const files: Record<string, Uint8Array> = {};
  for (const [path, data] of generatedResources) files[flatResourceName(path)] = data;

  const sourceName = document.origin.sourceName.replace(/\.[^.]+$/, "");
  return {
    blob: new Blob([zipSync(files, { level: 9 })], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(sourceName)}.resources.zip`,
  };
}

export function flatResourceName(path: string): string {
  return path.slice("assets/".length).replaceAll("/", "+");
}
