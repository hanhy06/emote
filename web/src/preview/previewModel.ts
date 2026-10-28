import type { ConversionAnimation, ConversionDocument } from "../domain/conversionDocument";
import { evaluatePose } from "../domain/animationIRPose";
import { matrix4ToRowMajor } from "../format/matrix";
import { PreviewMolangEvaluator } from "./molangEvaluator";
import type { ScalarEvaluatorIR } from "../domain/animationIRCurves";
import type { SelectedFrame } from "../workspace";

export interface PreviewPart {
  candidateId: string;
  partIndex: number;
  matrix: readonly number[];
  conversionMatrix?: readonly number[];
}

export type PreviewAvailability =
  | { status: "full" }
  | { status: "approximate"; reason: string }
  | { status: "unavailable"; reason: string };

export interface PreviewModel {
  availability: PreviewAvailability | null;
  candidateParts: Pick<PreviewPart, "candidateId" | "partIndex">[];
  parts: PreviewPart[];
}

export function createPreviewModel(document: ConversionDocument, animation: ConversionAnimation | undefined, frame: SelectedFrame): PreviewModel {
  const nodeIds = new Set(animation?.nodeIds ?? []);
  const candidates = Object.entries(document.skinCandidates).filter(([, candidate]) => nodeIds.has(candidate.nodeId));
  const groups = new Map<string, number>();
  const candidateParts = candidates.map(([candidateId, candidate]) => {
    if (!groups.has(candidate.groupId)) groups.set(candidate.groupId, groups.size);
    return { candidateId, partIndex: groups.get(candidate.groupId)! };
  });
  const reasons = new Set<string>();
  const approximate = (reason: string) => { reasons.add(reason); };
  const evaluator = new PreviewMolangEvaluator();
  const evaluate: ScalarEvaluatorIR = (value, progress, base = 0, resolveValue): number => {
    try {
      return evaluator.evaluate(typeof value === "number" ? value : value.molang, { animationTime: time, keyframeLerpTime: progress, lifeTime: time, resolveValue });
    } catch (reason) {
      approximate(reason instanceof Error ? reason.message : String(reason));
      return base;
    }
  };
  const time = frame.type === "timeline" ? frame.tick / 20 : 0;
  const parts: PreviewPart[] = [];
  let availability: PreviewAvailability | null = animation ? { status: "full" } : null;
  if (animation) {
    if (animation.clip.clock?.type === "molang") approximate("Runtime animation clock is approximated with elapsed time.");
    if (animation.clip.molang?.initialize || animation.clip.molang?.tick) approximate("Runtime Molang is preserved; dependent expressions use their base components.");
    try {
      const poses = evaluatePose({ ...animation, nodes: Object.fromEntries(animation.nodeIds.map((id) => [id, document.nodes[id]])),
        animation: frame.type === "create" ? { ...animation.clip, tracks: [] } : animation.clip }, time, evaluate, approximate);
      for (const { candidateId, partIndex } of candidateParts) {
        const candidate = document.skinCandidates[candidateId];
        const pose = poses[candidate.nodeId];
        if (!pose?.attachments[candidate.attachmentId]) continue;
        parts.push({ candidateId, partIndex, matrix: matrix4ToRowMajor(pose.matrix, candidateId),
          ...(candidate.fittingOperation ? { conversionMatrix: candidate.fittingOperation.value } : {}) });
      }
      if (reasons.size) availability = { status: "approximate", reason: [...reasons].join(" ") };
    } catch (reason) {
      availability = { status: "unavailable", reason: reason instanceof Error ? reason.message : String(reason) };
    }
  }
  return { availability, candidateParts, parts };
}
