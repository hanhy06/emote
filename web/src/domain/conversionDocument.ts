import type { ConversionIssue } from "../foundation/diagnostics";
import type { EmoteMetadata, EmotePlayerBehavior, NodeSpace, PlayerSkinPart } from "../format/emoteAnimation";
import { normalizeResourceLocation } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import type { GeneratedResource } from "./generatedResource";
import type {
  ImportedAnimation,
  ImportedNode,
  ImportedProject,
  ImportedSkinPart,
  ImportSource,
} from "./conversionSeed";

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
    part: PlayerSkinPart;
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
    minecraftVersion?: string;
  };
  targetMinecraftVersion: string;
  nodes: Record<string, ConversionNode>;
  skinGroups: Record<string, SkinGroup>;
  animations: ConversionAnimation[];
  sequence: SequenceOutputSettings;
  diagnostics: ConversionIssue[];
  resources: Map<string, GeneratedResource>;
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
    origin: { source: project.source, sourceName: project.sourceName, adapterLabel, ...(project.suggestedMinecraftVersion ? { minecraftVersion: project.suggestedMinecraftVersion } : {}) },
    targetMinecraftVersion: project.suggestedMinecraftVersion && Object.hasOwn(MINECRAFT_VERSION_PROFILES, project.suggestedMinecraftVersion)
      ? project.suggestedMinecraftVersion : DEFAULT_TARGET_MINECRAFT_VERSION,
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

export function documentPartAssignments(document: ConversionDocument): Record<string, PlayerSkinPart | null> {
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [[nodeId, document.skinGroups[node.skinGroupId]?.assignment?.part ?? null]]
    : []));
}

export function documentPartOrders(document: ConversionDocument): Record<string, number | null> {
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [[nodeId, document.skinGroups[node.skinGroupId]?.assignment?.order ?? null]]
    : []));
}

export function assignDocumentSkinPart(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  part: PlayerSkinPart | null,
): ConversionDocument {
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  if (selectedGroupIds.size === 0) return document;
  const order = selectedGroupIds.size === 1 && part !== null
    ? new Set(Object.entries(document.skinGroups)
      .filter(([groupId, group]) => !selectedGroupIds.has(groupId) && group.assignment?.part === part)
      .map(([groupId]) => groupId)).size
    : null;
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) {
    const group = skinGroups[groupId];
    skinGroups[groupId] = {
      ...group,
      assignment: part === null ? null : { part, order: order ?? group.assignment?.order ?? 0 },
    };
  }
  const selectedSpaceGroups = selectedSpaceAssignmentGroups(document, selectedNodeIds);
  const nodes = Object.fromEntries(Object.entries(document.nodes).map(([nodeId, node]) => [
    nodeId,
    part !== null && node.space === "scene" && selectedSpaceGroups.has(node.spaceAssignmentGroup ?? nodeId)
      ? { ...node, space: "initiator" as const }
      : node,
  ])) as ConversionDocument["nodes"];
  return { ...document, nodes, skinGroups };
}

export function assignDocumentSkinOrder(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  order: number,
): ConversionDocument {
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) {
    const group = skinGroups[groupId];
    if (group.assignment) skinGroups[groupId] = { ...group, assignment: { ...group.assignment, order } };
  }
  return { ...document, skinGroups };
}

export function assignDocumentNodeSpace(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  space: NodeSpace,
): ConversionDocument {
  const selectedGroups = selectedSpaceAssignmentGroups(document, selectedNodeIds);
  const nodes = Object.fromEntries(Object.entries(document.nodes).map(([nodeId, node]) => [
    nodeId,
    selectedGroups.has(node.spaceAssignmentGroup ?? nodeId) ? { ...node, space } : node,
  ])) as ConversionDocument["nodes"];
  if (space !== "scene") return { ...document, nodes };
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) skinGroups[groupId] = { ...skinGroups[groupId], assignment: null };
  return { ...document, nodes, skinGroups };
}

export function editDocumentAnimation(
  document: ConversionDocument,
  animationIndex: number,
  edit: (animation: ImportedAnimation) => ImportedAnimation,
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex
      ? { ...animation, source: edit(animation.source) }
      : animation),
  };
}

export function updateDocumentAnimationOutput(
  document: ConversionDocument,
  animationIndex: number,
  output: AnimationOutputSettings,
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex ? { ...animation, output } : animation),
  };
}

function isSkinCandidate(node: ImportedItemNode): boolean {
  return Boolean(node.skin || node.suggestedSkin || node.playerHeadConversion || normalizeResourceLocation(node.itemStack.id) === "minecraft:player_head");
}

function selectedSkinGroupIds(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>): Set<string> {
  return new Set([...selectedNodeIds].flatMap((nodeId) => {
    const node = document.nodes[nodeId];
    return node?.type === "item_display" && node.skinGroupId ? [node.skinGroupId] : [];
  }));
}

function selectedSpaceAssignmentGroups(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>): Set<string> {
  return new Set([...selectedNodeIds].flatMap((nodeId) => {
    const node = document.nodes[nodeId];
    return node ? [node.spaceAssignmentGroup ?? nodeId] : [];
  }));
}
