import {
  assignDocumentNodeSpace,
  assignDocumentSkinOrder,
  assignDocumentSkinPart,
  updateDocumentAnimationOutput,
} from "./domain/conversionEditor";
import {
  createConversionDocument,
  documentPartAssignments,
  type AnimationOutputSettings,
  type ConversionDocument,
  type ConversionNode,
} from "./domain/conversionDocument";
import type { NodeSpace } from "./format/emoteAnimation";
import type { ImportedAnimation, ImportedProject } from "./domain/conversionSeed";
import { isVisibleAtTick, type PlayerHeadPart } from "./preview/playerHeadPart";
import type { SkinPartId } from "./preview/skinAssignment";

type ConversionItemNode = Extract<ConversionNode, { type: "item_display" }>;

export interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: ConversionItemNode;
}

export interface ConverterSession {
  document: ConversionDocument;
  animationIndex: number;
  previewFrameIndex: number;
  selectedParts: Set<string>;
}

export const EMPTY_SELECTION = new Set<string>();

export function createConverterSession(project: ImportedProject, adapterLabel: string): ConverterSession {
  return {
    document: createConversionDocument(project, adapterLabel),
    animationIndex: 0,
    previewFrameIndex: 0,
    selectedParts: new Set(),
  };
}

export function selectSessionAnimation(session: ConverterSession, animationIndex: number): ConverterSession {
  if (!session.document.animations[animationIndex]) return session;
  return { ...session, animationIndex, previewFrameIndex: 0, selectedParts: new Set() };
}

export function updateSessionAnimationOptions(session: ConverterSession, output: AnimationOutputSettings): ConverterSession {
  return { ...session, document: updateDocumentAnimationOutput(session.document, session.animationIndex, output) };
}

export function updateSessionAnimation(
  session: ConverterSession,
  edit: (animation: ImportedAnimation) => ImportedAnimation,
): ConverterSession {
  return {
    ...session,
    document: {
      ...session.document,
      animations: session.document.animations.map((animation, index) => index === session.animationIndex
        ? { ...animation, source: edit(animation.source) }
        : animation),
    },
  };
}

export function findSkinCandidates(document: ConversionDocument | null): SkinCandidate[] {
  if (!document) return [];
  const candidates = Object.entries(document.nodes).flatMap(([nodeId, node]) => node.type === "item_display" && node.skinGroupId
    ? [{ nodeId, partIndex: 0, node }]
    : []);
  const partIndexByGroup = new Map<string, number>();
  return candidates.map((candidate) => {
    const group = candidate.node.skinGroupId!;
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
    let sourceMatrix = candidate.node.defaultMatrix;
    if (tick !== null) {
      const transforms = animation?.tracks[candidate.nodeId]?.transforms;
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

export function assignSessionSkinPart(session: ConverterSession, part: SkinPartId | null): ConverterSession {
  return { ...session, document: assignDocumentSkinPart(session.document, session.selectedParts, part) };
}

export function assignSessionSpace(session: ConverterSession, space: NodeSpace): ConverterSession {
  return { ...session, document: assignDocumentNodeSpace(session.document, session.selectedParts, space) };
}

export function assignSessionOrder(session: ConverterSession, order: number): ConverterSession {
  return { ...session, document: assignDocumentSkinOrder(session.document, session.selectedParts, order) };
}

export function assignmentSummary(document: ConversionDocument): string {
  const assignments = documentPartAssignments(document);
  const assigned = Object.values(document.skinGroups).filter((group) => group.nodeIds.every((nodeId) => assignments[nodeId])).length;
  const skin = Object.keys(document.skinGroups).length
    ? `${assigned}/${Object.keys(document.skinGroups).length} skin parts assigned`
    : "No skin assignment needed";
  return document.resources.size ? `${skin} · ${document.resources.size} resource files` : skin;
}
