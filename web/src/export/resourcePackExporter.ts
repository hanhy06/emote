import { strToU8, zipSync } from "fflate";
import type { ImportedProject, ImportedSkinPart } from "../import/types";
import { compileExportAnimation, sanitizeAnimationFileName, validateResourceVersion } from "./projectExporter";
import type { ExportOptions, ExportResult } from "./types";

const GENERATED_RESOURCE_PATH_PATTERN = /^assets\/[a-z0-9_.-]+\/[a-z0-9_./-]+$/;

export function exportResourcePack(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
): ExportResult {
  const generatedResources = generatedResourceFiles(project, options.minecraftVersion);
  const animation = compileExportAnimation(project, options, skinAssignments, animationIndex);
  const packFormat = resourcePackFormat(project.resourceMinecraftVersion ?? options.minecraftVersion);
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
  const bytes = zipSync(files, { level: 9 });
  return {
    blob: new Blob([bytes], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(animation.metadata.name)}.zip`,
  };
}

export function generatedResourceFiles(project: ImportedProject, minecraftVersion: string): ReadonlyMap<string, Uint8Array> {
  if (project.resources.size === 0) throw new Error("This emote does not contain generated resources.");
  validateResourceVersion(project, minecraftVersion);
  for (const path of project.resources.keys()) {
    if (path === "pack.mcmeta") throw new Error("Generated resources cannot replace pack.mcmeta.");
    const segments = path.split("/");
    if (
      !GENERATED_RESOURCE_PATH_PATTERN.test(path)
      || path.includes("\\")
      || segments.some((segment) => !segment || segment === "." || segment === "..")
    ) {
      throw new Error(`Generated resource has an invalid pack path: ${path}`);
    }
  }
  return project.resources;
}

function resourcePackFormat(minecraftVersion: string): [number, number] {
  if (minecraftVersion === "26.2") return [88, 0];
  throw new Error(`Resource pack metadata is not configured for Minecraft ${minecraftVersion}.`);
}
