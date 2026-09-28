import {
  documentNodeSpaces,
  documentPartAssignments,
  documentPartOrders,
  type ConversionDocument,
  type ConversionNode,
} from "../domain/conversionDocument";
import type { PreviewAvailability, PreviewNodeTrack, PreviewProjection } from "../domain/previewProjection";
import type { NodeSpace, PlayerSkinPart } from "../domain/player";

type ConversionItemNode = Extract<ConversionNode, { type: "item_display" }>;

interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: ConversionItemNode;
}

export interface PreviewPart {
  nodeId: string;
  partIndex: number;
  matrix: readonly number[];
  conversionMatrix?: readonly number[];
}

export interface PreviewModel {
  tick: number | null;
  durationTicks: number;
  availability: PreviewAvailability | null;
  parts: PreviewPart[];
  assignments: Record<string, PlayerSkinPart | null>;
  orders: Record<string, number | null>;
  spaces: Record<string, NodeSpace>;
  hasReviewNodes: boolean;
}

export function createPreviewModel(
  document: ConversionDocument,
  nodeIds: readonly string[],
  projection: PreviewProjection | undefined,
  previewFrameIndex: number,
): PreviewModel {
  const availability = projection?.availability ?? null;
  const durationTicks = projection?.durationTicks ?? 0;
  const tick = availability?.status !== "full" || previewFrameIndex === 0
    ? null
    : Math.min(previewFrameIndex - 1, Math.max(0, durationTicks));
  const scopedNodeIds = new Set(nodeIds);
  const scopedNodes = Object.fromEntries(Object.entries(document.nodes).filter(([nodeId]) => scopedNodeIds.has(nodeId)));
  const candidates = findSkinCandidates(scopedNodes);

  return {
    tick,
    durationTicks,
    availability,
    parts: createPreviewParts(candidates, projection, tick),
    assignments: pickNodeValues(documentPartAssignments(document), scopedNodeIds),
    orders: pickNodeValues(documentPartOrders(document), scopedNodeIds),
    spaces: pickNodeValues(documentNodeSpaces(document), scopedNodeIds),
    hasReviewNodes: candidates.length > 0,
  };
}

function pickNodeValues<T>(values: Record<string, T>, nodeIds: ReadonlySet<string>): Record<string, T> {
  return Object.fromEntries(Object.entries(values).filter(([nodeId]) => nodeIds.has(nodeId)));
}

function findSkinCandidates(nodes: Readonly<Record<string, ConversionNode>>): SkinCandidate[] {
  const candidates = Object.entries(nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.binding.skinGroupId
    ? [{ nodeId, partIndex: 0, node }]
    : []);
  const partIndexByGroup = new Map<string, number>();
  return candidates.map((candidate) => {
    const group = candidate.node.binding.skinGroupId!;
    if (!partIndexByGroup.has(group)) partIndexByGroup.set(group, partIndexByGroup.size);
    return { ...candidate, partIndex: partIndexByGroup.get(group)! };
  });
}

function createPreviewParts(
  candidates: SkinCandidate[],
  projection: PreviewProjection | undefined,
  tick: number | null,
): PreviewPart[] {
  const previewTracks = projection?.tracks;
  return candidates.filter((candidate) => isVisibleAtTick(
    candidate.node.visible,
    previewTracks?.[candidate.nodeId],
    tick,
  )).map((candidate) => {
    let sourceMatrix = candidate.node.defaultMatrix;
    if (tick !== null) {
      const transforms = previewTracks?.[candidate.nodeId]?.transforms;
      for (let index = (transforms?.length ?? 0) - 1; index >= 0; index--) {
        const transform = transforms?.[index];
        if (!transform || transform.tick > tick) continue;
        sourceMatrix = transform.matrix;
        break;
      }
    }
    return {
      nodeId: candidate.nodeId,
      partIndex: candidate.partIndex,
      matrix: sourceMatrix,
      ...(candidate.node.playerHeadConversion ? { conversionMatrix: candidate.node.playerHeadConversion.matrix } : {}),
    };
  });
}

function isVisibleAtTick(defaultVisible: boolean, track: PreviewNodeTrack | undefined, tick: number | null): boolean {
  if (tick === null) return defaultVisible;
  return track?.visibility.filter((keyframe) => keyframe.tick <= tick).at(-1)?.visible ?? defaultVisible;
}
