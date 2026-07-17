import { strToU8, zipSync } from "fflate";
import { compileImportedAnimation } from "../compiler/animationCompiler";
import type { EmoteAnimation, EmoteMetadata } from "../format/emoteAnimation";
import { serializeEmoteAnimation } from "../format/serializer";
import type { ImportedProject, ImportedSkinPart } from "../import/types";

export interface ExportOptions extends EmoteMetadata {
  minecraftVersion: string;
  namespace: string;
  playbackMode: "source" | EmoteAnimation["timeline"]["loop"];
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
  validateArtifactVersion(project, options.minecraftVersion);
  const animation = compileExportAnimation(project, options, skinAssignments, animationIndex);
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeFileName(animation.metadata.name)}.json`,
  };
}

export function exportResourcePack(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
): ExportResult {
  if (project.artifacts.size === 0) throw new Error("This emote does not contain generated resources.");
  validateArtifactVersion(project, options.minecraftVersion);
  const animation = compileExportAnimation(project, options, skinAssignments, animationIndex);
  const packFormat = resourcePackFormat(project.artifactMinecraftVersion ?? options.minecraftVersion);
  const files: Record<string, Uint8Array> = {
    "pack.mcmeta": strToU8(`${JSON.stringify({
      pack: {
        description: `${animation.metadata.name} emote resources`,
        min_format: packFormat,
        max_format: packFormat,
      },
    }, null, 2)}\n`),
  };
  for (const [path, data] of project.artifacts) {
    if (path.startsWith("/") || path.split("/").includes("..") || path.includes("\\")) {
      throw new Error(`Generated resource has an invalid pack path: ${path}`);
    }
    if (files[path]) throw new Error(`Generated resource pack contains a duplicate path: ${path}`);
    files[path] = data;
  }
  const bytes = zipSync(files, { level: 9 });
  return {
    blob: new Blob([bytes], { type: "application/zip" }),
    fileName: `emote.${sanitizeFileName(animation.metadata.name)}.zip`,
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

function compileExportAnimation(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
): EmoteAnimation {
  return compileImportedAnimation(applySkinAssignments(project, skinAssignments), {
    minecraftVersion: options.minecraftVersion,
    namespace: options.namespace,
    ...(options.playbackMode === "source" ? {} : { loop: options.playbackMode }),
    metadata: {
      name: options.name,
      description: options.description,
      hide_player: options.hide_player,
    },
  }, animationIndex);
}

function validateArtifactVersion(project: ImportedProject, minecraftVersion: string): void {
  if (project.artifactMinecraftVersion && minecraftVersion !== project.artifactMinecraftVersion) {
    throw new Error(`Generated resources require Minecraft ${project.artifactMinecraftVersion}.`);
  }
}

function resourcePackFormat(minecraftVersion: string): [number, number] {
  if (minecraftVersion === "26.2") return [88, 0];
  throw new Error(`Resource pack metadata is not configured for Minecraft ${minecraftVersion}.`);
}

function sanitizeFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
