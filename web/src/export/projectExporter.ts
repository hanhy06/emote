import { strToU8, zipSync } from "fflate";
import { compileImportedAnimation } from "../compiler/animationCompiler";
import type { EmoteAnimation } from "../format/emoteAnimation";
import { multiplyMatrix16 } from "../format/matrix";
import { sanitizeResourcePath } from "../format/resourceLocation";
import { serializeEmoteAnimation } from "../format/serializer";
import { serializeSnbtCompound, serializeSnbtString } from "../format/snbt";
import type { ImportedNode, ImportedProject, ImportedSkinPart } from "../import/types";
import { validateResourceVersion } from "./generatedResources";
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

export function exportAnimationBundle(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  includeSequence: boolean,
): ExportResult {
  validateResourceVersion(project, options.minecraftVersion);
  const animations = project.animations.map((_, index) => compileExportAnimation(project, options, skinAssignments, index));
  const files: Record<string, Uint8Array> = Object.fromEntries(animations.map((animation, index) => [
    `emote.${index + 1}.${sanitizeAnimationFileName(animation.metadata.name)}.json`,
    strToU8(serializeEmoteAnimation(animation)),
  ]));
  if (includeSequence) {
    const first = animations[0];
    if (!first) throw new Error("The project does not contain animations.");
    const sequence = {
      type: "sequence",
      schema_version: 2,
      id: `${first.id.slice(0, first.id.indexOf(":"))}:${sanitizeResourcePath(`${options.name}_sequence`)}`,
      metadata: { ...options.additionalMetadata, name: `${options.name} Sequence`, description: options.description },
      settings: { cooldown: first.settings.cooldown, player: options.player },
      steps: animations.map((animation) => ({ emote: animation.id })),
    };
    files[`emote.${sanitizeAnimationFileName(options.name)}.sequence.json`] = strToU8(JSON.stringify(sequence));
  }
  return {
    blob: new Blob([zipSync(files)], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(options.name)}.${includeSequence ? "sequence" : "animations"}.zip`,
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
    standalone: options.standalone,
    cooldown: options.cooldown,
    loopDelay: options.loopDelay,
  }, animationIndex);
}

export function sanitizeAnimationFileName(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "") || "emote";
}
