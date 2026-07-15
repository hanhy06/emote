import JSZip from "jszip";
import type { ImportInput } from "../adapter";

export interface LoadedPack {
  files: Map<string, Uint8Array>;
}

export async function loadPack(input: ImportInput): Promise<LoadedPack> {
  const zip = await JSZip.loadAsync(input.bytes);
  const rawFiles = new Map<string, Uint8Array>();
  await Promise.all(Object.values(zip.files).filter((entry) => !entry.dir).map(async (entry) => {
    rawFiles.set(entry.name.replaceAll("\\", "/").replace(/^\.\//, ""), await entry.async("uint8array"));
  }));
  const root = findPackRoot(rawFiles.keys());
  return {
    files: new Map([...rawFiles].filter(([path]) => path.startsWith(root)).map(([path, data]) => [path.slice(root.length), data])),
  };
}

export function isZip(bytes: Uint8Array): boolean {
  return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;
}

function findPackRoot(paths: Iterable<string>): string {
  const candidates = [...paths]
    .filter((path) => path === "pack.mcmeta" || path.endsWith("/pack.mcmeta"))
    .map((path) => path.slice(0, -"pack.mcmeta".length))
    .sort((first, second) => first.split("/").length - second.split("/").length || first.localeCompare(second));
  if (candidates.length === 0) throw new Error("Could not find pack.mcmeta in the ZIP file.");
  return candidates[0];
}
