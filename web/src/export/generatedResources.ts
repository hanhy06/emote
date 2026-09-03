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
    const options = typeof frame.value === "string" ? [frame.value] : frame.value.options;
    return options.some(referencesGeneratedModel);
  }));
}

export function generatedResourceFiles(project: GeneratedResourceSource, minecraftVersion: string): ReadonlyMap<string, Uint8Array> {
  if (project.resources.size === 0) throw new Error("This emote does not contain generated resources.");
  const profile = minecraftVersionProfile(minecraftVersion);
  const encoder = new TextEncoder();
  const files = new Map<string, Uint8Array>();
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
