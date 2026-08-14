import { strToU8, zipSync } from "fflate";
import { compileConversionAnimation, compileImportedAnimation } from "../compiler/animationCompiler";
import type { ConversionDocument } from "../domain/conversionDocument";
import type { EmoteAnimation, NodeSpace } from "../format/emoteAnimation";
import { multiplyMatrix16 } from "../format/matrix";
import { formatMinecraftTime, parseMinecraftTime } from "../format/minecraftTime";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { serializeEmoteAnimation } from "../format/serializer";
import { serializeSnbtCompound, serializeSnbtString } from "../format/snbt";
import type { ImportedNode, ImportedProject, ImportedSkinPart } from "../import/types";
import { validateResourceVersion } from "./generatedResources";
import type { ExportOptions, ExportResult } from "./types";

const PLAYER_HEAD_SNBT = serializeSnbtCompound([
  ["id", serializeSnbtString("minecraft:player_head")],
  ["count", "1"],
]);

export function exportDocumentAnimation(document: ConversionDocument, animationIndex: number): ExportResult {
  validateResourceVersion(document, document.targetMinecraftVersion);
  const animation = compileConversionAnimation(document, animationIndex);
  const displayName = document.animations[animationIndex]?.output.displayName ?? "emote";
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeAnimationFileName(displayName)}.json`,
  };
}

export function exportDocumentAnimationBundle(document: ConversionDocument, includeSequence: boolean): ExportResult {
  validateResourceVersion(document, document.targetMinecraftVersion);
  const firstEntry = document.animations[0];
  if (!firstEntry) throw new Error("The project does not contain animations.");
  const animations = document.animations.map((_, index) => compileConversionAnimation(
    document,
    index,
    includeSequence ? { standalone: false } : undefined,
  ));
  const files: Record<string, Uint8Array> = Object.fromEntries(animations.map((animation, index) => [
    `emote.${index + 1}.${sanitizeAnimationFileName(document.animations[index].output.displayName)}.json`,
    strToU8(serializeEmoteAnimation(animation)),
  ]));
  if (includeSequence) {
    const sequenceOutput = document.sequence;
    const sequence = {
      type: "sequence",
      schema_version: 3,
      id: `${sanitizeNamespace(sequenceOutput.namespace)}:${sanitizeResourcePath(sequenceOutput.displayName)}`,
      metadata: { ...sequenceOutput.additionalMetadata, name: sequenceOutput.displayName, description: sequenceOutput.description },
      settings: { cooldown: formatMinecraftTime(parseMinecraftTime(sequenceOutput.cooldown)), player: sequenceOutput.player },
      steps: animations.map((animation) => ({ emote: animation.id })),
    };
    files[`emote.${sanitizeAnimationFileName(sequenceOutput.displayName)}.sequence.json`] = strToU8(`${JSON.stringify(sequence, null, 2)}\n`);
  }
  return {
    blob: new Blob([zipSync(files)], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(includeSequence ? document.sequence.displayName : firstEntry.output.displayName)}${includeSequence ? "" : ".animations"}.zip`,
  };
}

export function exportAnimation(
  project: ImportedProject,
  options: ExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
  nodeSpaces: Readonly<Record<string, NodeSpace>> = {},
): ExportResult {
  validateResourceVersion(project, options.minecraftVersion);
  const animation = compileExportAnimation(project, options, skinAssignments, animationIndex, nodeSpaces);
  return {
    blob: new Blob([serializeEmoteAnimation(animation)], { type: "application/json" }),
    fileName: `emote.${sanitizeAnimationFileName(options.name)}.json`,
  };
}

export function exportAnimationBundle(
  project: ImportedProject,
  optionsByAnimation: readonly ExportOptions[],
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  includeSequence: boolean,
  nodeSpaces: Readonly<Record<string, NodeSpace>> = {},
): ExportResult {
  const firstOptions = optionsByAnimation[0];
  if (!firstOptions) throw new Error("The project does not contain animations.");
  optionsByAnimation.forEach((options) => validateResourceVersion(project, options.minecraftVersion));
  const animations = project.animations.map((_, index) => {
    const options = optionsByAnimation[index];
    if (!options) throw new Error(`Animation ${index + 1} does not have export settings.`);
    return compileExportAnimation(project, includeSequence ? { ...options, standalone: false } : options, skinAssignments, index, nodeSpaces);
  });
  const files: Record<string, Uint8Array> = Object.fromEntries(animations.map((animation, index) => [
    `emote.${index + 1}.${sanitizeAnimationFileName(optionsByAnimation[index].name)}.json`,
    strToU8(serializeEmoteAnimation(animation)),
  ]));
  if (includeSequence) {
    const first = animations[0];
    if (!first) throw new Error("The project does not contain animations.");
    const sequence = {
      type: "sequence",
      schema_version: 3,
      id: `${first.id.slice(0, first.id.indexOf(":"))}:${sanitizeResourcePath(firstOptions.name)}`,
      metadata: { ...firstOptions.additionalMetadata, name: firstOptions.name, description: firstOptions.description },
      settings: { cooldown: first.settings.cooldown, player: firstOptions.player },
      steps: animations.map((animation) => ({ emote: animation.id })),
    };
    files[`emote.${sanitizeAnimationFileName(firstOptions.name)}.sequence.json`] = strToU8(`${JSON.stringify(sequence, null, 2)}\n`);
  }
  return {
    blob: new Blob([zipSync(files)], { type: "application/zip" }),
    fileName: `emote.${sanitizeAnimationFileName(firstOptions.name)}${includeSequence ? "" : ".animations"}.zip`,
  };
}

function applyNodeAssignments(
  project: ImportedProject,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  nodeSpaces: Readonly<Record<string, NodeSpace>>,
): ImportedProject {
  const conversions = new Map<string, ImportedNode["defaultMatrix"]>();
  const nodes = Object.fromEntries(Object.entries(project.nodes).map(([id, originalNode]) => {
    const node = { ...originalNode, space: nodeSpaces[id] ?? originalNode.space } as ImportedNode;
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
  nodeSpaces: Readonly<Record<string, NodeSpace>> = {},
): EmoteAnimation {
  return compileImportedAnimation(applyNodeAssignments(project, skinAssignments, nodeSpaces), {
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
