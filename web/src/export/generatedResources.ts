import type { GeneratedResource } from "../domain/generatedResource";
import { minecraftVersionProfile } from "../format/minecraftVersionProfiles";

interface GeneratedResourceSource {
  resources: ReadonlyMap<string, GeneratedResource>;
}

const GENERATED_RESOURCE_PATH_PATTERN = /^assets\/[a-z0-9_.-]+\/[a-z0-9_./-]+$/;

export function generatedResourceFiles(
  project: GeneratedResourceSource,
  minecraftVersion: string,
  resourceReferences?: ReadonlySet<string>,
): ReadonlyMap<string, Uint8Array> {
  if (project.resources.size === 0) throw new Error("This emote does not contain generated resources.");
  const profile = minecraftVersionProfile(minecraftVersion);
  const encoder = new TextEncoder();
  const files = new Map<string, Uint8Array>();
  const includedPaths = resourceReferences ? referencedResourcePaths(resourceReferences, project.resources) : undefined;
  for (const [path, resource] of project.resources) {
    if (path === "pack.mcmeta") throw new Error("Generated resources cannot replace pack.mcmeta.");
    const segments = path.split("/");
    if (
      !GENERATED_RESOURCE_PATH_PATTERN.test(path)
      || path.includes("\\")
      || segments.some((segment) => !segment || segment === "." || segment === "..")
    ) {
      throw new Error(`Generated resource has an invalid pack path: ${path}`);
    }
    if (includedPaths && !includedPaths.has(path)) continue;
    if (resource instanceof Uint8Array) {
      files.set(path, resource);
      continue;
    }
    const value = resource.kind === "cuboid_model" ? { textures: resource.textures, elements: resource.elements }
      : resource.kind === "item_model" ? { model: { type: profile.resources.itemModelType, model: resource.model } }
      : resource.value;
    files.set(path, encoder.encode(`${JSON.stringify(value, null, 2)}\n`));
  }
  return files;
}

function referencedResourcePaths(
  references: ReadonlySet<string>,
  resources: ReadonlyMap<string, GeneratedResource>,
): ReadonlySet<string> {
  for (const reference of references) {
    if (!resources.has(reference)) throw new Error(`Animation references a missing generated resource: ${reference}`);
  }
  const included = new Set<string>();
  const pending = [...references];
  while (pending.length > 0) {
    const path = pending.pop()!;
    if (included.has(path) || !resources.has(path)) continue;
    included.add(path);
    const resource = resources.get(path)!;
    if (resource instanceof Uint8Array || resource.kind === "json") continue;
    if (resource.kind === "item_model") {
      const modelPath = modelResourcePath(resource.model);
      if (modelPath) pending.push(modelPath);
      continue;
    }
    for (const texture of Object.values(resource.textures)) {
      const texturePath = textureResourcePath(texture);
      if (!texturePath) continue;
      pending.push(texturePath);
      if (resources.has(`${texturePath}.mcmeta`)) pending.push(`${texturePath}.mcmeta`);
    }
  }
  return included;
}

function modelResourcePath(id: string): string | undefined {
  const separator = id.indexOf(":");
  if (separator < 1 || separator === id.length - 1) return undefined;
  return `assets/${id.slice(0, separator)}/models/${id.slice(separator + 1)}.json`;
}

function textureResourcePath(id: string): string | undefined {
  if (id.startsWith("#")) return undefined;
  const separator = id.indexOf(":");
  if (separator < 1 || separator === id.length - 1) return undefined;
  return `assets/${id.slice(0, separator)}/textures/${id.slice(separator + 1)}.png`;
}
