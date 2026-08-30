import type { EmoteAnimation } from "../format/emoteAnimation";

interface GeneratedResourceSource {
  resources: Map<string, Uint8Array>;
  resourceMinecraftVersion?: string;
}

const GENERATED_RESOURCE_PATH_PATTERN = /^assets\/[a-z0-9_.-]+\/[a-z0-9_./-]+$/;

export function animationUsesGeneratedResources(
  animation: EmoteAnimation,
  resources: ReadonlyMap<string, Uint8Array>,
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

export function validateResourceVersion(project: GeneratedResourceSource, minecraftVersion: string): void {
  if (project.resourceMinecraftVersion && minecraftVersion !== project.resourceMinecraftVersion) {
    throw new Error(`Generated resources require Minecraft ${project.resourceMinecraftVersion}.`);
  }
}
