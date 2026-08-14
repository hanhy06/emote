import type { ExportOptions } from "./export/types";
import type { NodeSpace } from "./format/emoteAnimation";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart } from "./import/types";
import { isVisibleAtTick, type PlayerHeadPart } from "./preview/playerHeadPart";
import {
  assignSkinPart,
  isPlayerHeadItemStack,
  type PartAssignments,
  type PartOrders,
  type SkinPartId,
} from "./preview/skinAssignment";

export interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: Extract<ImportedNode, { type: "item_display" }>;
}

export interface ConverterSession {
  project: ImportedProject;
  adapterLabel: string;
  animationIndex: number;
  previewFrameIndex: number;
  assignments: PartAssignments;
  orders: PartOrders;
  spaces: Record<string, NodeSpace>;
  selectedParts: Set<string>;
  animationOptions: ExportOptions[];
  conversionError: string;
}

export const EMPTY_ASSIGNMENTS: PartAssignments = {};
export const EMPTY_ORDERS: PartOrders = {};
export const EMPTY_SELECTION = new Set<string>();

export function createConverterSession(project: ImportedProject, adapterLabel: string): ConverterSession {
  const candidates = findSkinCandidates(project);
  return {
    project,
    adapterLabel,
    animationIndex: 0,
    previewFrameIndex: 0,
    selectedParts: new Set(),
    assignments: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, (candidate.node.suggestedSkin ?? candidate.node.skin)?.part ?? null])),
    orders: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, (candidate.node.suggestedSkin ?? candidate.node.skin)?.order ?? null])),
    spaces: Object.fromEntries(Object.entries(project.nodes).map(([nodeId, node]) => {
      const skin = node.type === "item_display" ? node.suggestedSkin ?? node.skin : null;
      return [nodeId, node.space ?? skin?.participant ?? (skin ? "initiator" : "scene")];
    })),
    animationOptions: project.animations.map((animation) => ({
      minecraftVersion: project.suggestedMinecraftVersion ?? "26.2",
      namespace: project.suggestedNamespace ?? project.suggestedMetadata.name,
      playbackMode: "source",
      name: project.suggestedMetadata.name,
      description: project.suggestedMetadata.description,
      player: project.suggestedPlayer,
      additionalMetadata: Object.fromEntries(Object.entries(project.suggestedMetadata)
        .filter(([key]) => key !== "name" && key !== "description")),
      standalone: project.suggestedStandalone ?? true,
      cooldown: project.suggestedCooldown ?? "0t",
      loopDelay: `${animation.loopDelayTicks}t`,
    })),
    conversionError: "",
  };
}

export function selectSessionAnimation(session: ConverterSession, animationIndex: number): ConverterSession {
  if (!session.animationOptions[animationIndex]) return session;
  return { ...session, animationIndex, previewFrameIndex: 0, selectedParts: new Set() };
}

export function updateSessionAnimationOptions(session: ConverterSession, options: ExportOptions): ConverterSession {
  return {
    ...session,
    animationOptions: session.animationOptions.map((current, index) => index === session.animationIndex ? options : current),
  };
}

export function findSkinCandidates(project: ImportedProject | null): SkinCandidate[] {
  if (!project) return [];
  const candidates = Object.entries(project.nodes).flatMap(([nodeId, node]) => {
    if (node.type !== "item_display") return [];
    let isPlayerHead = false;
    try {
      isPlayerHead = isPlayerHeadItemStack(node.itemStackSnbt);
    } catch {
      isPlayerHead = false;
    }
    if (!isPlayerHead && !node.playerHeadConversion) return [];
    return [{ nodeId, partIndex: 0, node }];
  });
  const partIndexByGroup = new Map<string, number>();
  return candidates.map((candidate) => {
    const group = candidate.node.skinAssignmentGroup ?? candidate.nodeId;
    if (!partIndexByGroup.has(group)) partIndexByGroup.set(group, partIndexByGroup.size);
    return { ...candidate, partIndex: partIndexByGroup.get(group)! };
  });
}

export function createPreviewParts(
  candidates: SkinCandidate[],
  animation: ImportedAnimation | undefined,
  tick: number | null,
): PlayerHeadPart[] {
  return candidates.filter((candidate) => isVisibleAtTick(
    candidate.node.visible,
    animation?.tracks[candidate.nodeId],
    tick,
  )).map((candidate) => {
    const sourceMatrix = tick === null
      ? candidate.node.defaultMatrix
      : animation?.tracks[candidate.nodeId]?.transforms.filter((keyframe) => keyframe.tick <= tick).at(-1)?.matrix
        ?? candidate.node.defaultMatrix;
    return {
      nodeId: candidate.nodeId,
      partIndex: candidate.partIndex,
      matrix: sourceMatrix,
      ...(candidate.node.playerHeadConversion ? { conversionMatrix: candidate.node.playerHeadConversion.matrix } : {}),
    };
  });
}

export function assignSessionSkinPart(session: ConverterSession, candidates: SkinCandidate[], part: SkinPartId | null): ConverterSession {
  const selected = [...session.selectedParts];
  const assigned = assignSkinPart(session.assignments, session.orders, selected, part, skinAssignmentGroups(candidates));
  const spaces = part === null ? session.spaces : {
    ...session.spaces,
    ...Object.fromEntries(selected.map((nodeId) => [nodeId, session.spaces[nodeId] === "scene" ? "initiator" : session.spaces[nodeId]])),
  };
  return { ...session, ...assigned, spaces };
}

export function assignSessionSpace(session: ConverterSession, candidates: SkinCandidate[], space: NodeSpace): ConverterSession {
  const selected = [...session.selectedParts];
  return {
    ...session,
    spaces: { ...session.spaces, ...Object.fromEntries(selected.map((nodeId) => [nodeId, space])) },
    ...(space === "scene" ? assignSkinPart(
      session.assignments,
      session.orders,
      selected,
      null,
      skinAssignmentGroups(candidates),
    ) : {}),
  };
}

export function assignSessionOrder(session: ConverterSession, candidates: SkinCandidate[], order: number): ConverterSession {
  const groups = skinAssignmentGroups(candidates);
  const selectedGroups = new Set([...session.selectedParts].map((nodeId) => groups[nodeId]));
  const nodeIds = candidates
    .map((candidate) => candidate.nodeId)
    .filter((nodeId) => selectedGroups.has(groups[nodeId]) && session.assignments[nodeId] != null);
  return nodeIds.length === 0 ? session : {
    ...session,
    orders: { ...session.orders, ...Object.fromEntries(nodeIds.map((nodeId) => [nodeId, order])) },
  };
}

export function buildSkinAssignments(session: ConverterSession, candidates: SkinCandidate[]): Record<string, ImportedSkinPart | null> {
  const skins: Record<string, ImportedSkinPart | null> = {};
  for (const candidate of candidates) {
    const part = session.assignments[candidate.nodeId];
    const participant = session.spaces[candidate.nodeId] === "partner" ? "partner" : "initiator";
    skins[candidate.nodeId] = part ? { participant, part, order: session.orders[candidate.nodeId] ?? 0 } : null;
  }
  return skins;
}

export function assignmentSummary(candidates: SkinCandidate[], assignments: PartAssignments, resourceCount: number): string {
  const groups = new Map<string, SkinCandidate[]>();
  candidates.forEach((candidate) => {
    const group = candidate.node.skinAssignmentGroup ?? candidate.nodeId;
    groups.set(group, [...groups.get(group) ?? [], candidate]);
  });
  const assigned = [...groups.values()].filter((group) => group.every((candidate) => assignments[candidate.nodeId])).length;
  const skin = groups.size ? `${assigned}/${groups.size} skin parts assigned` : "No skin assignment needed";
  return resourceCount ? `${skin} · ${resourceCount} resource files` : skin;
}

function skinAssignmentGroups(candidates: SkinCandidate[]): Record<string, string> {
  return Object.fromEntries(candidates.map((candidate) => [
    candidate.nodeId,
    candidate.node.skinAssignmentGroup ?? candidate.nodeId,
  ]));
}
