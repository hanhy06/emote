import type { EmoteAnimation } from "../format/emoteAnimation";
import type { GeneratedResource } from "../domain/generatedResource";
import { minecraftVersionProfile } from "../format/minecraftVersionProfiles";

interface GeneratedResourceSource {
  resources: ReadonlyMap<string, GeneratedResource>;
}

const GENERATED_RESOURCE_PATH_PATTERN = /^assets\/[a-z0-9_.-]+\/[a-z0-9_./-]+$/;

export function animationUsesGeneratedResources(
  animation: EmoteAnimation,
  resources: ReadonlyMap<string, GeneratedResource>,
): boolean {
  const itemModelIds: string[] = [];
  for (const path of resources.keys()) {
    const match = /^assets\/([^/]+)\/items\/(.+)\.json$/.exec(path);
    if (match) itemModelIds.push(`${match[1]}:${match[2]}`);
  }
  if (itemModelIds.length === 0) return false;

  const referencesGeneratedModel = (snbt: string | undefined) => snbt !== undefined
    && itemModelIds.some((id) => snbt.includes(`"${id}"`) || snbt.includes(`'${id}'`));
  if (Object.values(animation.nodes).some((node) => node.type === "item_display" && referencesGeneratedModel(node.item_stack_snbt))) {
    return true;
  }
  return Object.values(animation.timeline.tracks).some((track) => track.nbt?.some((frame) => {
    return referencesGeneratedModel(typeof frame.value === "string" ? frame.value : frame.value.molang);
  }));
}

export function generatedResourceFiles(
  project: GeneratedResourceSource,
  minecraftVersion: string,
  animations?: readonly EmoteAnimation[],
): ReadonlyMap<string, Uint8Array> {
  if (project.resources.size === 0) throw new Error("This emote does not contain generated resources.");
  const profile = minecraftVersionProfile(minecraftVersion);
  const encoder = new TextEncoder();
  const files = new Map<string, Uint8Array>();
  const includedPaths = animations ? referencedResourcePaths(animations, project.resources) : undefined;
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
  animations: readonly EmoteAnimation[],
  resources: ReadonlyMap<string, GeneratedResource>,
): ReadonlySet<string> {
  const included = new Set<string>();
  const itemModels = new Map<string, string>();
  for (const path of resources.keys()) {
    const match = /^assets\/([^/]+)\/items\/(.+)\.json$/.exec(path);
    if (match) itemModels.set(`${match[1]}:${match[2]}`, path);
  }

  for (const [id, itemPath] of itemModels) {
    if (!animations.some((animation) => animationReferencesItemModel(animation, id))) continue;
    included.add(itemPath);
    const item = resources.get(itemPath);
    if (!item || item instanceof Uint8Array || item.kind !== "item_model") continue;
    const modelPath = modelResourcePath(item.model);
    if (!modelPath || !resources.has(modelPath)) continue;
    included.add(modelPath);
    const model = resources.get(modelPath);
    if (!model || model instanceof Uint8Array || model.kind !== "cuboid_model") continue;
    for (const texture of Object.values(model.textures)) {
      const texturePath = textureResourcePath(texture);
      if (!texturePath || !resources.has(texturePath)) continue;
      included.add(texturePath);
      if (resources.has(`${texturePath}.mcmeta`)) included.add(`${texturePath}.mcmeta`);
    }
  }
  return included;
}

function animationReferencesItemModel(animation: EmoteAnimation, id: string): boolean {
  const references = (snbt: string | undefined) => snbt?.includes(`"${id}"`) === true || snbt?.includes(`'${id}'`) === true;
  if (Object.values(animation.nodes).some((node) => node.type === "item_display" && references(node.item_stack_snbt))) return true;
  return Object.values(animation.timeline.tracks).some((track) => track.nbt?.some((frame) => {
    return references(typeof frame.value === "string" ? frame.value : frame.value.molang);
  }));
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
