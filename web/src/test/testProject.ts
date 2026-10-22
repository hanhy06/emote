import { readFileSync } from "node:fs";
import type { ImportedProject } from "../domain/conversionSeed";
import { createConversionDocument } from "../domain/conversionDocument";
import { importFileBatch, type ImportFile } from "../import/importBatch";

export const TEST_MINECRAFT_VERSION = readFileSync(new URL("../../../gradle.properties", import.meta.url), "utf8")
  .match(/^minecraft_version=(.+)$/m)![1].trim();

export function createTestDocument(project: ImportedProject, formatLabel: string) {
  const document = createConversionDocument(project, formatLabel);
  document.targetMinecraftVersion = TEST_MINECRAFT_VERSION;
  return document;
}

export async function importTestFileBatch(files: readonly ImportFile[]) {
  const document = await importFileBatch(files);
  document.targetMinecraftVersion = TEST_MINECRAFT_VERSION;
  return document;
}
