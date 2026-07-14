import JSZip from "jszip";

export interface LoadedDatapack {
  fileName: string;
  rootPath: string;
  files: Map<string, Uint8Array>;
}

export async function loadDatapack(file: Blob & { name?: string }): Promise<LoadedDatapack> {
  const zip = await JSZip.loadAsync(await file.arrayBuffer());
  const files = new Map<string, Uint8Array>();

  await Promise.all(
    Object.values(zip.files)
      .filter((entry) => !entry.dir)
      .map(async (entry) => {
        files.set(normalizePath(entry.name), await entry.async("uint8array"));
      }),
  );

  const rootPath = findPackRoot(files.keys());
  return {
    fileName: file.name ?? "datapack.zip",
    rootPath,
    files: stripRootPath(files, rootPath),
  };
}

export function findPackRoot(paths: Iterable<string>): string {
  const candidates = [...paths]
    .filter((path) => path === "pack.mcmeta" || path.endsWith("/pack.mcmeta"))
    .map((path) => path.slice(0, -"pack.mcmeta".length))
    .sort((first, second) => first.split("/").length - second.split("/").length || first.localeCompare(second));

  if (candidates.length === 0) {
    throw new Error("Could not find pack.mcmeta in the ZIP file.");
  }
  return candidates[0];
}

function stripRootPath(files: Map<string, Uint8Array>, rootPath: string): Map<string, Uint8Array> {
  return new Map(
    [...files]
      .filter(([path]) => path.startsWith(rootPath))
      .map(([path, data]) => [path.slice(rootPath.length), data]),
  );
}

function normalizePath(path: string): string {
  return path.replaceAll("\\", "/").replace(/^\.\//, "");
}
