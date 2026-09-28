import type { ConversionError } from "../../foundation/diagnostics";
import type { BbDataPoint, BbKeyframe } from "./blockbenchCubeSchema";
import { easingProgress, sampleBezierAtX, sampleSpline } from "./curveMath";
import type { MolangBakeEvaluator } from "./molangBakeEvaluator";

type Vector3Tuple = [number, number, number];

interface TimedKeyframe {
  time: number;
  keyframe: BbKeyframe;
}

interface MolangContext {
  animationTime: number;
  keyframeLerpTime: number;
}

export interface BlockbenchChannelPolicy {
  bakeEvaluator: MolangBakeEvaluator;
  previewEvaluator: MolangBakeEvaluator;
  invalidPointCount(path: string): ConversionError;
  missingAxis(path: string): ConversionError;
  unsupportedInterpolation(channel: string, interpolation: string, path: string): ConversionError;
  unsupportedEasing(channel: string, easing: string, path: string): ConversionError;
  canFallbackFromBake(error: unknown): boolean;
}

export interface BlockbenchChannelEvaluator {
  evaluate(keyframes: BbKeyframe[], channel: string, time: number, fallback: number[], path: string): number[];
  canBake(keyframes: BbKeyframe[], channel: string, fallback: number[], path: string): boolean;
  evaluateApproximate(keyframes: BbKeyframe[], channel: string, time: number, fallback: number[], path: string): number[];
  isBakeFallbackError(error: unknown): boolean;
}

export function createBlockbenchChannelEvaluator(policy: BlockbenchChannelPolicy): BlockbenchChannelEvaluator {
  const evaluate = (keyframes: BbKeyframe[], channel: string, time: number, fallback: number[], path: string): number[] => {
    const frames = channelFrames(keyframes, channel);
    if (frames.length === 0) return [...fallback];
    const exact = frames.find((frame) => Math.abs(frame.time - time) < 1e-9);
    if (exact) return evaluatePoint(postPoint(exact.keyframe, policy, path), policy.bakeEvaluator, { animationTime: time, keyframeLerpTime: 1 }, policy, path);
    const afterIndex = frames.findIndex((frame) => frame.time > time);
    if (afterIndex === 0) return [...fallback];
    if (afterIndex < 0) return evaluatePoint(postPoint(frames.at(-1)!.keyframe, policy, path), policy.bakeEvaluator, { animationTime: time, keyframeLerpTime: 1 }, policy, path);

    const before = frames[afterIndex - 1];
    const after = frames[afterIndex];
    const alpha = (time - before.time) / (after.time - before.time);
    const context = { animationTime: time, keyframeLerpTime: alpha };
    const start = evaluatePoint(postPoint(before.keyframe, policy, path), policy.bakeEvaluator, context, policy, path);
    const end = evaluatePoint(prePoint(after.keyframe, policy, path), policy.bakeEvaluator, context, policy, path);
    if ((before.keyframe.interpolation ?? "linear") === "step") return start;
    if (before.keyframe.interpolation === "catmullrom" || after.keyframe.interpolation === "catmullrom") {
      return mapAxes((axis) => catmullRom(frames, afterIndex, axis, alpha, policy.bakeEvaluator, context, policy, path));
    }
    if (before.keyframe.interpolation === "bezier" || after.keyframe.interpolation === "bezier") {
      return mapAxes((axis) => bezier(before, after, start[axis], end[axis], axis, alpha));
    }
    const interpolation = after.keyframe.interpolation ?? "linear";
    if (interpolation !== "linear" && interpolation !== "step") throw policy.unsupportedInterpolation(channel, interpolation, path);
    const easing = after.keyframe.easing ?? "linear";
    const eased = easingProgress(easing, alpha, after.keyframe.easingArgs);
    if (eased === undefined) throw policy.unsupportedEasing(channel, easing, path);
    return mapAxes((axis) => start[axis] + (end[axis] - start[axis]) * eased);
  };

  const canBake = (keyframes: BbKeyframe[], channel: string, fallback: number[], path: string): boolean => {
    const frames = channelFrames(keyframes, channel).map(({ keyframe }) => keyframe);
    const times = frames.flatMap((frame, index) => {
      const previous = frames[index - 1];
      return previous ? [frame.time, (previous.time + frame.time) / 2, Math.max(previous.time, frame.time - 1e-7)] : [frame.time];
    });
    try {
      times.forEach((sampleTime) => evaluate(frames, channel, sampleTime, fallback, path));
      return true;
    } catch (error) {
      if (policy.canFallbackFromBake(error)) return false;
      throw error;
    }
  };

  const evaluateApproximate = (keyframes: BbKeyframe[], channel: string, time: number, fallback: number[], path: string): number[] => {
    const frames = channelFrames(keyframes, channel).map(({ keyframe }) => keyframe);
    if (frames.length === 0) return [...fallback];
    const exact = frames.find((frame) => Math.abs(frame.time - time) < 1e-9);
    if (exact) return evaluatePoint(postPoint(exact, policy, path), policy.previewEvaluator, { animationTime: time, keyframeLerpTime: 1 }, policy, path);
    const afterIndex = frames.findIndex((frame) => frame.time > time);
    if (afterIndex === 0) return [...fallback];
    if (afterIndex < 0) return evaluatePoint(postPoint(frames.at(-1)!, policy, path), policy.previewEvaluator, { animationTime: time, keyframeLerpTime: 1 }, policy, path);
    const before = frames[afterIndex - 1];
    const after = frames[afterIndex];
    const alpha = (time - before.time) / (after.time - before.time);
    const context = { animationTime: time, keyframeLerpTime: alpha };
    const start = evaluatePoint(postPoint(before, policy, path), policy.previewEvaluator, context, policy, path);
    if ((before.interpolation ?? "linear") === "step") return start;
    const end = evaluatePoint(prePoint(after, policy, path), policy.previewEvaluator, context, policy, path);
    return mapAxes((axis) => start[axis] + (end[axis] - start[axis]) * alpha);
  };

  return { evaluate, canBake, evaluateApproximate, isBakeFallbackError: policy.canFallbackFromBake };
}

function channelFrames(keyframes: BbKeyframe[], channel: string): TimedKeyframe[] {
  return keyframes.filter((frame) => frame.channel === channel)
    .map((keyframe) => ({ time: keyframe.time, keyframe }))
    .sort((first, second) => first.time - second.time);
}

function catmullRom(
  frames: TimedKeyframe[],
  afterIndex: number,
  axis: number,
  alpha: number,
  evaluator: MolangBakeEvaluator,
  context: MolangContext,
  policy: BlockbenchChannelPolicy,
  path: string,
): number {
  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const beforePlus = frames[afterIndex - 2];
  const afterPlus = frames[afterIndex + 1];
  const points: Array<readonly [number, number]> = [];
  if (beforePlus && before.keyframe.data_points.length === 1) {
    points.push([beforePlus.time, evaluatePoint(postPoint(beforePlus.keyframe, policy, path), evaluator, context, policy, path)[axis]]);
  }
  points.push([before.time, evaluatePoint(postPoint(before.keyframe, policy, path), evaluator, context, policy, path)[axis]]);
  points.push([after.time, evaluatePoint(prePoint(after.keyframe, policy, path), evaluator, context, policy, path)[axis]]);
  if (afterPlus && after.keyframe.data_points.length === 1) {
    points.push([afterPlus.time, evaluatePoint(prePoint(afterPlus.keyframe, policy, path), evaluator, context, policy, path)[axis]]);
  }
  return sampleSpline(points, (alpha + (beforePlus ? 1 : 0)) / (points.length - 1));
}

function bezier(before: TimedKeyframe, after: TimedKeyframe, start: number, end: number, axis: number, alpha: number): number {
  const gap = after.time - before.time;
  const rightTime = clamp(before.keyframe.bezier_right_time?.[axis] ?? 0.1, 0, gap);
  const leftTime = clamp(after.keyframe.bezier_left_time?.[axis] ?? -0.1, -gap, 0);
  return sampleBezierAtX([
    [before.time, start],
    [before.time + rightTime, start + (before.keyframe.bezier_right_value?.[axis] ?? 0)],
    [after.time + leftTime, end + (after.keyframe.bezier_left_value?.[axis] ?? 0)],
    [after.time, end],
  ], before.time + gap * alpha);
}

function prePoint(keyframe: BbKeyframe, policy: BlockbenchChannelPolicy, path: string): BbDataPoint {
  return requiredPoint(keyframe, 0, policy, path);
}

function postPoint(keyframe: BbKeyframe, policy: BlockbenchChannelPolicy, path: string): BbDataPoint {
  return requiredPoint(keyframe, keyframe.data_points.length - 1, policy, path);
}

function requiredPoint(keyframe: BbKeyframe, index: number, policy: BlockbenchChannelPolicy, path: string): BbDataPoint {
  if (keyframe.data_points.length < 1 || keyframe.data_points.length > 2) throw policy.invalidPointCount(path);
  return keyframe.data_points[index];
}

function evaluatePoint(point: BbDataPoint, evaluator: MolangBakeEvaluator, context: MolangContext, policy: BlockbenchChannelPolicy, path: string): Vector3Tuple {
  if (point.x === undefined || point.y === undefined || point.z === undefined) throw policy.missingAxis(path);
  return [point.x, point.y, point.z].map((value) => evaluator.evaluate(value, { ...context, lifeTime: context.animationTime }, path)) as Vector3Tuple;
}

function mapAxes(mapper: (axis: number) => number): Vector3Tuple {
  return [mapper(0), mapper(1), mapper(2)];
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}
