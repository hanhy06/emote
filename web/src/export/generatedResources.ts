import type { ImportedProject } from "../import/types";

const GENERATED_RESOURCE_PATH_PATTERN = /^assets\/[a-z0-9_.-]+\/[a-z0-9_./-]+$/;

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

export function validateResourceVersion(project: ImportedProject, minecraftVersion: string): void {
  if (project.resourceMinecraftVersion && minecraftVersion !== project.resourceMinecraftVersion) {
    throw new Error(`Generated resources require Minecraft ${project.resourceMinecraftVersion}.`);
  }
}
