import { parseAnimationSeconds } from "../format/time";
import { documentPartAssignments, documentPartOrders, type ConversionAnimation, type ConversionDocument } from "../domain/conversionDocument";
import { evaluatePoseIR } from "../domain/animationIRPose";
import type { PreviewAvailability } from "../domain/previewProjection";
import type { PlayerSkinPart } from "../domain/player";
import { matrix4ToRowMajor } from "../format/matrix";
import { MolangBakeEvaluator } from "./molangEvaluator";

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
  hasReviewNodes: boolean;
}

export function createPreviewModel(document: ConversionDocument, animation: ConversionAnimation | undefined, previewFrameIndex: number): PreviewModel {
  const nodeIds = new Set(animation?.nodeIds ?? []);
  const candidates = Object.entries(document.skinCandidates).filter(([id]) => nodeIds.has(id));
  const reasons = new Set<string>();
  const approximate = (reason: string) => { reasons.add(reason); };
  const evaluator = new MolangBakeEvaluator({ rejectNondeterministic: true, error: {
    code: "animation_preview", previewUnavailable: true, message: (expression) => `Expression uses its base component: ${expression}`,
  } });
  const evaluate = (value: number | { molang: string }, progress: number, base = 0): number => {
    try {
      return evaluator.evaluate(typeof value === "number" ? value : value.molang, { animationTime: time, keyframeLerpTime: progress, lifeTime: elapsed }, "preview");
    } catch (reason) {
      approximate(reason instanceof Error ? reason.message : String(reason));
      return base;
    }
  };
  let time = 0, elapsed = 0;
  const delay = animation?.clip.playback?.start_delay;
  const startDelay = delay === undefined ? 0 : Math.max(0, typeof delay === "string" ? parseAnimationSeconds(delay) : evaluate(delay, 0));
  const durationTicks = animation ? Math.ceil((parseAnimationSeconds(animation.clip.duration) + startDelay) * 20) : 0;
  const tick = previewFrameIndex === 0 ? null : Math.min(previewFrameIndex - 1, durationTicks);
  elapsed = (tick ?? 0) / 20;
  time = Math.min(animation ? parseAnimationSeconds(animation.clip.duration) : 0, Math.max(0, elapsed - startDelay));
  const parts: PreviewPart[] = [];
  let availability: PreviewAvailability | null = animation ? { status: "full" } : null;
  if (animation) {
    if (animation.clip.clock?.type === "molang") approximate("Runtime animation clock is approximated with elapsed time.");
    if (animation.clip.programs?.initialize || animation.clip.programs?.update) approximate("Runtime programs are preserved; dependent expressions use their base components.");
    try {
      const poses = evaluatePoseIR({ ...animation, nodes: Object.fromEntries(animation.nodeIds.map((id) => [id, document.nodes[id]])),
        animation: tick === null || elapsed < startDelay ? { ...animation.clip, tracks: [] } : animation.clip }, time, evaluate, approximate);
      const groups = new Map<string, number>();
      for (const [id, candidate] of candidates) {
        if (!groups.has(candidate.groupId)) groups.set(candidate.groupId, groups.size);
        const pose = poses[id];
        if (!pose?.attachments[candidate.attachmentId]) continue;
        const fitted = document.nodes[id].transform?.some((operation) => operation.id === candidate.fittingOperation?.id);
        parts.push({ nodeId: id, partIndex: groups.get(candidate.groupId)!, matrix: matrix4ToRowMajor(pose.matrix, id),
          ...(!fitted && candidate.fittingOperation ? { conversionMatrix: candidate.fittingOperation.value } : {}) });
      }
      if (reasons.size) availability = { status: "approximate", reason: [...reasons].join(" ") };
    } catch (reason) {
      availability = { status: "unavailable", reason: reason instanceof Error ? reason.message : String(reason) };
    }
  }
  return { tick, durationTicks, availability, parts,
    assignments: Object.fromEntries(Object.entries(documentPartAssignments(document)).filter(([id]) => nodeIds.has(id))),
    orders: Object.fromEntries(Object.entries(documentPartOrders(document)).filter(([id]) => nodeIds.has(id))),
    hasReviewNodes: candidates.length > 0 };
}
