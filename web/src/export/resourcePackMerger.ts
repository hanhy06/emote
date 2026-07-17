import { unzipSync, zipSync } from "fflate";
import type { ImportedProject } from "../import/types";
import type { ExportOptions, ExportResult } from "./projectExporter";
import { generatedResourceFiles } from "./projectExporter";

interface FolderFile {
  name: string;
  webkitRelativePath?: string;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export async function mergeResourcePackZip(
  project: ImportedProject,
  options: ExportOptions,
  file: File,
): Promise<ExportResult> {
  let entries: Record<string, Uint8Array>;
  try {
    entries = unzipSync(new Uint8Array(await file.arrayBuffer()));
  } catch {
    throw new Error("The selected file is not a readable ZIP resource pack.");
  }
  return mergeEntries(project, options, entries, stripZipExtension(file.name));
}

export async function mergeResourcePackFolder(
  project: ImportedProject,
  options: ExportOptions,
  selectedFiles: readonly FolderFile[],
): Promise<ExportResult> {
  if (selectedFiles.length === 0) throw new Error("The selected resource pack folder is empty.");
  const entries: Record<string, Uint8Array> = {};
  for (const file of selectedFiles) {
    const path = normalizePath(file.webkitRelativePath || file.name);
    entries[path] = new Uint8Array(await file.arrayBuffer());
  }
  const folderName = normalizePath(selectedFiles[0].webkitRelativePath || selectedFiles[0].name).split("/")[0];
  return mergeEntries(project, options, entries, folderName);
}

function mergeEntries(
  project: ImportedProject,
  options: ExportOptions,
  sourceEntries: Readonly<Record<string, Uint8Array>>,
  sourceName: string,
): ExportResult {
  const normalizedEntries = new Map<string, Uint8Array>();
  for (const [rawPath, data] of Object.entries(sourceEntries)) {
    const path = normalizePath(rawPath);
    if (!path || path.endsWith("/")) continue;
    validateInputPath(path);
    normalizedEntries.set(path, data);
  }

  const packRoot = findPackRoot([...normalizedEntries.keys()]);
  const files: Record<string, Uint8Array> = {};
  for (const [path, data] of normalizedEntries) {
    if (!path.startsWith(packRoot)) continue;
    const packPath = path.slice(packRoot.length);
    if (packPath) files[packPath] = data;
  }
  for (const [path, data] of generatedResourceFiles(project, options.minecraftVersion)) {
    files[path] = data;
  }

  return {
    blob: new Blob([zipSync(files, { level: 9 })], { type: "application/zip" }),
    fileName: `${sanitizeFileName(sourceName)}.emote-merged.zip`,
  };
}

function findPackRoot(paths: readonly string[]): string {
  const metadataPaths = paths.filter((path) => path === "pack.mcmeta" || path.endsWith("/pack.mcmeta"));
  if (metadataPaths.length === 0) {
    throw new Error("Could not find pack.mcmeta in the selected ZIP or folder.");
  }
  if (metadataPaths.length > 1) {
    throw new Error("The selected ZIP or folder contains more than one resource pack.");
  }
  return metadataPaths[0].slice(0, -"pack.mcmeta".length);
}

function normalizePath(path: string): string {
  return path.replaceAll("\\", "/").replace(/^\.\//, "").replace(/\/{2,}/g, "/");
}

function validateInputPath(path: string): void {
  if (path.startsWith("/") || path.split("/").includes("..")) {
    throw new Error(`The resource pack contains an unsafe path: ${path}`);
  }
}

function stripZipExtension(name: string): string {
  return name.replace(/\.zip$/i, "");
}

function sanitizeFileName(value: string): string {
  return value.replace(/[<>:"/\\|?*\u0000-\u001f]+/g, "_").replace(/[. ]+$/g, "").trim() || "resource-pack";
}
