import { compileImportedAnimation } from "../compiler/animationCompiler";
import type { EmoteAnimation } from "../format/emoteAnimation";
import { serializeEmoteAnimation } from "../format/serializer";
import { multiplyMatrix16 } from "../format/matrix";
import { serializeSnbtCompound, serializeSnbtString } from "../format/snbt";
import type { ImportedNode, ImportedProject, ImportedSkinPart } from "../import/types";
import type { ExportOptions, ExportResult } from "./types";

const PLAYER_HEAD_SNBT = serializeSnbtCompound([
  ["id", serializeSnbtString("minecraft:player_head")],
  ["count", "1"],
]);

export function exportAnimation(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
): ExportResult {
  validateResourceVersion(project, options.minecraftVersion);
  const animation = compileExportAnimation(project, options, skinAssignments, animationIndex);
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeAnimationFileName(animation.metadata.name)}.json`,
  };
}

function applySkinAssignments(
  project: ImportedProject,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
): ImportedProject {
  const conversions = new Map<string, ImportedNode["defaultMatrix"]>();
  const nodes = Object.fromEntries(Object.entries(project.nodes).map(([id, node]) => {
    if (node.type !== "item_display" || !Object.hasOwn(skinAssignments, id)) return [id, node];
    const skin = skinAssignments[id];
    const { skin: _oldSkin, suggestedSkin: _suggestedSkin, ...withoutSkin } = node;
    if (!skin) return [id, withoutSkin];
    if (!node.playerHeadConversion) return [id, { ...withoutSkin, skin }];

    const conversion = node.playerHeadConversion.matrix;
    conversions.set(id, conversion);
    const { playerHeadConversion: _conversion, ...convertedNode } = withoutSkin;
    return [id, {
      ...convertedNode,
      defaultMatrix: multiplyMatrix16(node.defaultMatrix, conversion, `Player head node ${id}`),
      itemStackSnbt: PLAYER_HEAD_SNBT,
      skin,
    }];
  }));
  const animations = project.animations.map((animation) => ({
    ...animation,
    tracks: Object.fromEntries(Object.entries(animation.tracks).map(([id, track]) => {
      const conversion = conversions.get(id);
      if (!conversion) return [id, track];
      return [id, {
        ...track,
        transforms: track.transforms.map((transform) => ({
          ...transform,
          matrix: multiplyMatrix16(transform.matrix, conversion, `Player head track ${animation.id}/${id}/${transform.tick}`),
        })),
      }];
    })),
  }));
  return { ...project, nodes, animations };
}

export function compileExportAnimation(
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
      ...options.additionalMetadata,
      name: options.name,
      description: options.description,
    },
    player: options.player,
  }, animationIndex);
}

export function validateResourceVersion(project: ImportedProject, minecraftVersion: string): void {
  if (project.resourceMinecraftVersion && minecraftVersion !== project.resourceMinecraftVersion) {
    throw new Error(`Generated resources require Minecraft ${project.resourceMinecraftVersion}.`);
  }
}

export function sanitizeAnimationFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
