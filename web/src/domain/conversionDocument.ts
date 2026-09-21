import type { ConversionIssue } from "../foundation/diagnostics";
import type { EmoteEvent, EmoteMetadata, EmotePlayerBehavior, NodeSpace, PlayerSkinPart } from "../format/emoteAnimation";
import { normalizeResourceLocation } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import type { GeneratedResource } from "./generatedResource";
import type { EditorNodeBinding } from "./nodeBindings";
import type {
  ImportedAnimation,
  ImportedNode,
  ImportedProject,
  ImportedSkinPart,
  ImportedTimelineEvent,
  ImportSource,
} from "./conversionSeed";

type ImportedItemNode = Extract<ImportedNode, { type: "item_display" }>;
type ImportedBlockNode = Extract<ImportedNode, { type: "block_display" }>;
type ImportedTextNode = Extract<ImportedNode, { type: "text_display" }>;
type ImportedAnchorNode = Extract<ImportedNode, { type: "anchor" }>;

export const DEFAULT_TARGET_MINECRAFT_VERSION = "26.3";

export type ConversionNode =
  | (Omit<ImportedItemNode, "binding" | "skin" | "suggestedSkin" | "space"> & {
    binding: EditorNodeBinding;
    space: NodeSpace;
  })
  | (Omit<ImportedBlockNode, "binding" | "space"> & { binding: EditorNodeBinding; space: NodeSpace })
  | (Omit<ImportedTextNode, "binding" | "space"> & { binding: EditorNodeBinding; space: NodeSpace })
  | (Omit<ImportedAnchorNode, "binding" | "space"> & { binding: EditorNodeBinding; space: NodeSpace });

export interface SkinGroup {
  nodeIds: string[];
  assignment: {
    part: PlayerSkinPart;
    order: number;
  } | null;
}

export interface AnimationOutputSettings {
  namespace: string;
  playbackMode: "source" | ImportedAnimation["playbackMode"];
  displayName: string;
  description: string;
  player: EmotePlayerBehavior;
  additionalMetadata: Record<string, unknown>;
  standalone: boolean;
  cooldown: string;
  rotationDeadzone: number;
  displayInterpolation: string;
  loopStart: string;
  loopEnd: string;
  loopDelay: string;
}

export interface ConversionAnimation {
  source: ImportedAnimation;
  events: ConversionAnimationEvents;
  output: AnimationOutputSettings;
  nodeIds: string[];
}

export interface ConversionAnimationEvents {
  start: EmoteEvent[];
  timeline: ImportedTimelineEvent[];
  loop: EmoteEvent[];
  stop: EmoteEvent[];
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
    const binding: EditorNodeBinding = { ...importedNode.binding, editorNodeId: nodeId };
    const suggestedSkin = importedNode.type === "item_display" ? importedNode.suggestedSkin ?? importedNode.skin : undefined;
    const space = importedNode.space ?? suggestedSkin?.participant ?? (suggestedSkin ? "initiator" : "scene");
    if (importedNode.type !== "item_display") {
      const { binding: _binding, space: _space, ...node } = importedNode;
      return [nodeId, { ...node, binding, space }];
    }

    const {
      binding: _binding,
      skin: _skin,
      suggestedSkin: _suggestedSkin,
      space: _space,
      ...itemNode
    } = importedNode;
    if (!isSkinCandidate(importedNode)) return [nodeId, { ...itemNode, binding, space }];
    const skinGroupId = importedNode.binding.skinGroupId ?? nodeId;
    const group = skinGroups[skinGroupId] ?? { nodeIds: [], assignment: null };
    group.nodeIds.push(nodeId);
    if (!group.assignment && suggestedSkin) {
      group.assignment = { part: suggestedSkin.part, order: suggestedSkin.order };
    }
    skinGroups[skinGroupId] = group;
    return [nodeId, { ...itemNode, binding: { ...binding, skinGroupId }, space }];
  })) as Record<string, ConversionNode>;

  const additionalMetadata = Object.fromEntries(Object.entries(project.suggestedMetadata)
    .filter(([key]) => key !== "name" && key !== "description"));
  const namespace = "emote";
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
        events: {
          start: [...animation.events.start],
          timeline: [...animation.events.timeline],
          loop: [...animation.events.loop],
          stop: [...animation.events.stop],
        },
        nodeIds: Object.keys(nodes),
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
          displayInterpolation: project.suggestedDisplayInterpolation ?? "1t",
          loopStart: `${animation.loopStartTicks ?? 0}t`,
          loopEnd: `${animation.loopEndTicks ?? 0}t`,
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
    if (node.type !== "item_display" || !node.binding.skinGroupId) continue;
    const assignment = document.skinGroups[node.binding.skinGroupId]?.assignment;
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
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.binding.skinGroupId
    ? [[nodeId, document.skinGroups[node.binding.skinGroupId]?.assignment?.part ?? null]]
    : []));
}

export function documentPartOrders(document: ConversionDocument): Record<string, number | null> {
  return Object.fromEntries(Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.binding.skinGroupId
    ? [[nodeId, document.skinGroups[node.binding.skinGroupId]?.assignment?.order ?? null]]
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
    part !== null && node.space === "scene" && selectedSpaceGroups.has(node.binding.spaceGroupId ?? nodeId)
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
    selectedGroups.has(node.binding.spaceGroupId ?? nodeId) ? { ...node, space } : node,
  ])) as ConversionDocument["nodes"];
  if (space !== "scene") return { ...document, nodes };
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) skinGroups[groupId] = { ...skinGroups[groupId], assignment: null };
  return { ...document, nodes, skinGroups };
}

export function updateDocumentAnimationLifecycleEvents(
  document: ConversionDocument,
  animationIndex: number,
  events: Pick<ConversionAnimationEvents, "start" | "loop" | "stop">,
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex
      ? { ...animation, events: { ...animation.events, ...events } }
      : animation),
  };
}

export function replaceDocumentAnimationTimelineEvents(
  document: ConversionDocument,
  animationIndex: number,
  tick: number,
  events: EmoteEvent[],
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex
      ? {
          ...animation,
          events: {
            ...animation.events,
            timeline: [
              ...animation.events.timeline.filter((event) => event.tick !== tick),
              ...events.map((event) => ({ ...event, tick })),
            ].sort((first, second) => first.tick - second.tick),
          },
        }
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
    return node?.type === "item_display" && node.binding.skinGroupId ? [node.binding.skinGroupId] : [];
  }));
}

function selectedSpaceAssignmentGroups(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>): Set<string> {
  return new Set([...selectedNodeIds].flatMap((nodeId) => {
    const node = document.nodes[nodeId];
    return node ? [node.binding.spaceGroupId ?? nodeId] : [];
  }));
}
