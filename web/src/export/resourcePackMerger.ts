import { unzip, zip } from "fflate";
import type { ImportedProject } from "../import/types";
import { generatedResourceFiles } from "./generatedResources";
import type { ExportOptions, ExportResult } from "./types";

interface FolderFile {
  name: string;
  webkitRelativePath?: string;
  arrayBuffer(): Promise<ArrayBuffer>;
}

const MAX_COMPRESSED_BYTES = 256 * 1024 * 1024;
const MAX_ENTRY_COUNT = 65_536;
const MAX_EXPANDED_BYTES = 256 * 1024 * 1024;

class ResourcePackBudget {
  private entryCount = 0;
  private expandedBytes = 0;

  add(path: string, size: number): void {
    this.entryCount++;
    if (this.entryCount > MAX_ENTRY_COUNT) {
      throw new Error(`The resource pack contains more than ${MAX_ENTRY_COUNT} entries.`);
    }
    if (!Number.isSafeInteger(size) || size < 0) {
      throw new Error(`The resource pack entry has an invalid size: ${path}`);
    }
    this.expandedBytes += size;
    if (this.expandedBytes > MAX_EXPANDED_BYTES) {
      throw new Error("The resource pack expands beyond the supported size limit.");
    }
  }
}

export async function mergeResourcePackZip(
  project: ImportedProject,
  options: ExportOptions,
  file: File,
): Promise<ExportResult> {
  if (file.size > MAX_COMPRESSED_BYTES) throw new Error("The selected ZIP exceeds the supported size limit.");
  const entries = await unzipResourcePack(new Uint8Array(await file.arrayBuffer()));
  return mergeEntries(project, options, entries, stripZipExtension(file.name));
}

export async function mergeResourcePackFolder(
  project: ImportedProject,
  options: ExportOptions,
  selectedFiles: readonly FolderFile[],
): Promise<ExportResult> {
  if (selectedFiles.length === 0) throw new Error("The selected resource pack folder is empty.");
  const budget = new ResourcePackBudget();
  const entries: Record<string, Uint8Array> = {};
  for (const file of selectedFiles) {
    const path = normalizePath(file.webkitRelativePath || file.name);
    const data = new Uint8Array(await file.arrayBuffer());
    budget.add(path, data.byteLength);
    entries[path] = data;
  }
  const folderName = normalizePath(selectedFiles[0].webkitRelativePath || selectedFiles[0].name).split("/")[0];
  return mergeEntries(project, options, entries, folderName);
}

async function mergeEntries(
  project: ImportedProject,
  options: ExportOptions,
  sourceEntries: Readonly<Record<string, Uint8Array>>,
  sourceName: string,
): Promise<ExportResult> {
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
    blob: new Blob([await zipResourcePack(files)], { type: "application/zip" }),
    fileName: `${sanitizeFileName(sourceName)}.emote-merged.zip`,
  };
}

function unzipResourcePack(bytes: Uint8Array): Promise<Record<string, Uint8Array>> {
  return new Promise((resolve, reject) => {
    const budget = new ResourcePackBudget();
    let limitError: Error | null = null;
    unzip(bytes, {
      filter: (entry) => {
        if (limitError) return false;
        try {
          budget.add(entry.name, entry.originalSize);
          return true;
        } catch (reason) {
          limitError = reason instanceof Error ? reason : new Error("The resource pack exceeds the supported limits.");
          return false;
        }
      },
    }, (error, entries) => {
      if (limitError) reject(limitError);
      else if (error) reject(new Error("The selected file is not a readable ZIP resource pack.", { cause: error }));
      else resolve(entries);
    });
  });
}

function zipResourcePack(files: Record<string, Uint8Array>): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    zip(files, { level: 9 }, (error, data) => {
      if (error) reject(new Error("The merged resource pack could not be compressed.", { cause: error }));
      else resolve(data as Uint8Array<ArrayBuffer>);
    });
  });
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
  const segments = path.split("/");
  if (
    path.startsWith("/")
    || /^[A-Za-z]:/.test(path)
    || path.includes("\0")
    || segments.some((segment) => segment === "." || segment === "..")
  ) {
    throw new Error(`The resource pack contains an unsafe path: ${path}`);
  }
}

function stripZipExtension(name: string): string {
  return name.replace(/\.zip$/i, "");
}

function sanitizeFileName(value: string): string {
  return value.replace(/[<>:"/\\|?*\u0000-\u001f]+/g, "_").replace(/[. ]+$/g, "").trim() || "resource-pack";
}
