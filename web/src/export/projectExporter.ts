import { compileImportedProject } from "../compiler/animationCompiler";
import type { EmoteMetadata } from "../format/emoteAnimation";
import { serializeEmoteAnimation } from "../format/serializer";
import type { ImportedProject, ImportedSkinPart } from "../import/types";

export interface ExportOptions extends EmoteMetadata {
  minecraftVersion: string;
  namespace: string;
}

export interface ExportResult {
  blob: Blob;
  fileName: string;
}

export function exportAnimation(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
): ExportResult {
  if (project.artifacts.length > 0 && options.minecraftVersion !== "26.2") {
    throw new Error("Animated Java model resources currently support Minecraft 26.2 only.");
  }
  const animations = compileImportedProject(applySkinAssignments(project, skinAssignments), {
    minecraftVersion: options.minecraftVersion,
    namespace: options.namespace,
    metadata: {
      name: options.name,
      description: options.description,
      command_name: options.command_name,
      hide_player: options.hide_player,
    },
  });
  const animation = animations[animationIndex];
  if (!animation) throw new Error(`Animation ${animationIndex + 1} does not exist.`);
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeFileName(animation.metadata.command_name)}.json`,
  };
}

export function exportResource(project: ImportedProject, minecraftVersion: string, resourceIndex: number): ExportResult {
  if (minecraftVersion !== "26.2") {
    throw new Error("Animated Java model resources currently support Minecraft 26.2 only.");
  }
  const resource = project.artifacts[resourceIndex];
  if (!resource) throw new Error(`Resource ${resourceIndex + 1} does not exist.`);
  const bytes = new Uint8Array(resource.data).buffer;
  return {
    blob: new Blob([bytes], { type: contentType(resource.path) }),
    fileName: resource.path.split("/").at(-1) ?? "resource.bin",
  };
}

export function downloadExport(result: ExportResult): void {
  const url = URL.createObjectURL(result.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = result.fileName;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function applySkinAssignments(
  project: ImportedProject,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
): ImportedProject {
  return {
    ...project,
    nodes: Object.fromEntries(Object.entries(project.nodes).map(([id, node]) => {
      if (node.type !== "item_display" || !Object.hasOwn(skinAssignments, id)) return [id, node];
      const skin = skinAssignments[id];
      const { skin: _oldSkin, ...withoutSkin } = node;
      return [id, skin ? { ...withoutSkin, skin } : withoutSkin];
    })),
  };
}

function contentType(path: string): string {
  if (path.endsWith(".json")) return "application/json";
  if (path.endsWith(".png")) return "image/png";
  return "application/octet-stream";
}

function sanitizeFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
