import type { ConversionIssue } from "../foundation/diagnostics";
import type { EmoteMetadata, EmotePlayerBehavior, NodeSpace } from "../format/emoteAnimation";
import { normalizeResourceLocation } from "../format/resourceLocation";
import { readSnbtRawField, readSnbtStringField } from "../format/snbt";
import type {
  ImportedAnimation,
  ImportedNode,
  ImportedProject,
  ImportedSkinPart,
  ImportSource,
} from "./conversionSeed";
import type { SkinPartId } from "../preview/skinAssignment";

type ImportedItemNode = Extract<ImportedNode, { type: "item_display" }>;
type ImportedBlockNode = Extract<ImportedNode, { type: "block_display" }>;
type ImportedTextNode = Extract<ImportedNode, { type: "text_display" }>;
type ImportedAnchorNode = Extract<ImportedNode, { type: "anchor" }>;

export const DEFAULT_TARGET_MINECRAFT_VERSION = "26.2";

export type ConversionNode =
  | (Omit<ImportedItemNode, "id" | "skin" | "suggestedSkin" | "skinAssignmentGroup" | "space"> & {
    space: NodeSpace;
    skinGroupId?: string;
  })
  | (Omit<ImportedBlockNode, "id" | "skinAssignmentGroup" | "space"> & { space: NodeSpace })
  | (Omit<ImportedTextNode, "id" | "skinAssignmentGroup" | "space"> & { space: NodeSpace })
  | (Omit<ImportedAnchorNode, "id" | "skinAssignmentGroup" | "space"> & { space: NodeSpace });

export interface SkinGroup {
  nodeIds: string[];
  assignment: {
    part: SkinPartId;
    order: number;
  } | null;
}

export interface AnimationOutputSettings {
  namespace: string;
  playbackMode: "source" | ImportedAnimation["loop"];
  displayName: string;
  description: string;
  player: EmotePlayerBehavior;
  additionalMetadata: Record<string, unknown>;
  standalone: boolean;
  cooldown: string;
  rotationDeadzone: number;
  loopDelay: string;
}

export interface ConversionAnimation {
  source: ImportedAnimation;
  output: AnimationOutputSettings;
}

export interface SequenceOutputSettings {
  namespace: string;
  displayName: string;
  description: string;
  additionalMetadata: Record<string, unknown>;
  cooldown: string;
  player: EmotePlayerBehavior;
}

export interface ConversionDocument {
  origin: {
    source: ImportSource;
    sourceName: string;
    adapterLabel: string;
  };
  targetMinecraftVersion: string;
  nodes: Record<string, ConversionNode>;
  skinGroups: Record<string, SkinGroup>;
  animations: ConversionAnimation[];
  sequence: SequenceOutputSettings;
  diagnostics: ConversionIssue[];
  resources: Map<string, Uint8Array>;
  resourceMinecraftVersion?: string;
}

export function createConversionDocument(project: ImportedProject, adapterLabel: string): ConversionDocument {
  const skinGroups: Record<string, SkinGroup> = {};
  const nodes = Object.fromEntries(Object.entries(project.nodes).map(([nodeId, importedNode]) => {
    const suggestedSkin = importedNode.type === "item_display" ? importedNode.suggestedSkin ?? importedNode.skin : undefined;
    const space = importedNode.space ?? suggestedSkin?.participant ?? (suggestedSkin ? "initiator" : "scene");
    if (importedNode.type !== "item_display") {
      const { id: _id, skinAssignmentGroup: _skinAssignmentGroup, space: _space, ...node } = importedNode;
      return [nodeId, { ...node, space }];
    }

    const {
      id: _id,
      skin: _skin,
      suggestedSkin: _suggestedSkin,
      skinAssignmentGroup: _skinAssignmentGroup,
      space: _space,
      ...itemNode
    } = importedNode;
    if (!isSkinCandidate(importedNode)) return [nodeId, { ...itemNode, space }];
    const skinGroupId = importedNode.skinAssignmentGroup ?? nodeId;
    const group = skinGroups[skinGroupId] ?? { nodeIds: [], assignment: null };
    group.nodeIds.push(nodeId);
    if (!group.assignment && suggestedSkin) {
      group.assignment = { part: suggestedSkin.part, order: suggestedSkin.order };
    }
    skinGroups[skinGroupId] = group;
    return [nodeId, { ...itemNode, space, skinGroupId }];
  })) as Record<string, ConversionNode>;

  const additionalMetadata = Object.fromEntries(Object.entries(project.suggestedMetadata)
    .filter(([key]) => key !== "name" && key !== "description"));
  const namespace = project.suggestedNamespace ?? project.suggestedMetadata.name;
  return {
    origin: { source: project.source, sourceName: project.sourceName, adapterLabel },
    targetMinecraftVersion: project.suggestedMinecraftVersion ?? DEFAULT_TARGET_MINECRAFT_VERSION,
    nodes,
    skinGroups,
    animations: project.animations.map((animation) => {
      const metadata = animation.suggestedMetadata;
      const animationAdditionalMetadata = metadata
        ? Object.fromEntries(Object.entries(metadata).filter(([key]) => key !== "name" && key !== "description"))
        : additionalMetadata;
      return {
        source: animation,
        output: {
          namespace,
          playbackMode: "source",
          displayName: metadata?.name ?? animation.name,
          description: metadata?.description ?? `${animation.name} emote.`,
          player: project.suggestedPlayer,
          additionalMetadata: animationAdditionalMetadata,
          standalone: project.suggestedStandalone ?? true,
          cooldown: project.suggestedCooldown ?? "0t",
          rotationDeadzone: project.suggestedRotationDeadzone ?? 50,
          loopDelay: `${animation.loopDelayTicks}t`,
        },
      };
    }),
    sequence: {
      namespace,
      displayName: project.suggestedMetadata.name,
      description: project.suggestedMetadata.description,
      additionalMetadata,
      cooldown: project.suggestedCooldown ?? "0t",
      player: project.suggestedPlayer,
    },
    diagnostics: project.diagnostics,
    resources: project.resources,
    ...(project.resourceMinecraftVersion ? { resourceMinecraftVersion: project.resourceMinecraftVersion } : {}),
  };
}

export function documentMetadata(settings: AnimationOutputSettings): EmoteMetadata {
  return { ...settings.additionalMetadata, name: settings.displayName, description: settings.description };
}

export function documentSkinAssignments(document: ConversionDocument): Record<string, ImportedSkinPart | null> {
  const entries: Array<[string, ImportedSkinPart | null]> = [];
  for (const [nodeId, node] of Object.entries(document.nodes)) {
    if (node.type !== "item_display" || !node.skinGroupId) continue;
    const assignment = document.skinGroups[node.skinGroupId]?.assignment;
    entries.push([nodeId, assignment ? {
      participant: node.space === "partner" ? "partner" : "initiator",
      part: assignment.part,
      order: assignment.order,
    } : null]);
  }
  return Object.fromEntries(entries);
}

export function documentNodeSpaces(document: ConversionDocument): Record<string, NodeSpace> {
  return Object.fromEntries(Object.entries(document.nodes).map(([nodeId, node]) => [nodeId, node.space]));
}

export function documentPartAssignments(document: ConversionDocument): Record<string, SkinPartId | null> {
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [[nodeId, document.skinGroups[node.skinGroupId]?.assignment?.part ?? null]]
    : []));
}

export function documentPartOrders(document: ConversionDocument): Record<string, number | null> {
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [[nodeId, document.skinGroups[node.skinGroupId]?.assignment?.order ?? null]]
    : []));
}

export function isPlayerHeadItemStack(itemStackSnbt: string): boolean {
  const quotedId = readSnbtStringField(itemStackSnbt, "id");
  const rawId = quotedId === null ? readSnbtRawField(itemStackSnbt, "id") : null;
  const id = quotedId ?? (rawId && /^[A-Za-z0-9._+-]+$/.test(rawId) ? rawId : null);
  return id !== null && normalizeResourceLocation(id) === "minecraft:player_head";
}

function isSkinCandidate(node: ImportedItemNode): boolean {
  return Boolean(node.skin || node.suggestedSkin || node.playerHeadConversion || isPlayerHeadItemStack(node.itemStackSnbt));
}
