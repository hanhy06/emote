import { CubicBezierCurve, SplineCurve, Vector2 } from "three";
import { ConversionError } from "../../foundation/diagnostics";
import { MolangBakeEvaluator } from "../molangBakeEvaluator";
import type { BbDataPoint, BbKeyframe } from "./cubeProjectSchema";
import { cubeEasingProgress } from "../animationEasing";

type Vector3Tuple = [number, number, number];

interface TimedKeyframe {
  time: number;
  keyframe: BbKeyframe;
}

interface MolangContext {
  animationTime: number;
  keyframeLerpTime: number;
}

export function evaluateGeckoChannel(
  keyframes: BbKeyframe[],
  channel: string,
  time: number,
  fallback: number[],
  path: string,
): number[] {
  const frames = keyframes
    .filter((frame) => frame.channel === channel)
    .map((keyframe) => ({ time: keyframe.time, keyframe }))
    .sort((first, second) => first.time - second.time);
  if (frames.length === 0) return [...fallback];
  const evaluator = createMolangEvaluator();
  const exact = frames.find((frame) => Math.abs(frame.time - time) < 1e-9);
  if (exact) return evaluatePoint(postPoint(exact.keyframe), evaluator, { animationTime: time, keyframeLerpTime: 1 }, path);
  const afterIndex = frames.findIndex((frame) => frame.time > time);
  if (afterIndex === 0) return [...fallback];
  if (afterIndex < 0) return evaluatePoint(postPoint(frames[frames.length - 1].keyframe), evaluator, { animationTime: time, keyframeLerpTime: 1 }, path);

  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const alpha = (time - before.time) / (after.time - before.time);
  const context = { animationTime: time, keyframeLerpTime: alpha };
  const start = evaluatePoint(postPoint(before.keyframe), evaluator, context, path);
  const end = evaluatePoint(prePoint(after.keyframe), evaluator, context, path);
  if ((before.keyframe.interpolation ?? "linear") === "step") return start;
  if (before.keyframe.interpolation === "catmullrom" || after.keyframe.interpolation === "catmullrom") {
    return mapAxes((axis) => catmullRom(frames, afterIndex, axis, alpha, evaluator, context, path));
  }
  if (before.keyframe.interpolation === "bezier" || after.keyframe.interpolation === "bezier") {
    return mapAxes((axis) => bezier(before, after, start[axis], end[axis], axis, alpha));
  }
  const interpolation = after.keyframe.interpolation ?? "linear";
  if (interpolation !== "linear" && interpolation !== "step") {
    throw new ConversionError("unsupported_geckolib_interpolation", `GeckoLib ${channel} keyframe uses unsupported ${interpolation} interpolation.`, path);
  }
  const easing = after.keyframe.easing ?? "linear";
  const eased = cubeEasingProgress(easing, alpha, after.keyframe.easingArgs);
  if (eased === undefined) throw new ConversionError("unsupported_geckolib_easing", `GeckoLib ${channel} keyframe uses unsupported easing ${easing}.`, path);
  return mapAxes((axis) => start[axis] + (end[axis] - start[axis]) * eased);
}

function catmullRom(
  frames: TimedKeyframe[],
  afterIndex: number,
  axis: number,
  alpha: number,
  evaluator: MolangBakeEvaluator,
  context: MolangContext,
  path: string,
): number {
  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const beforePlus = frames[afterIndex - 2];
  const afterPlus = frames[afterIndex + 1];
  const points: Vector2[] = [];
  if (beforePlus && before.keyframe.data_points.length === 1) {
    points.push(new Vector2(beforePlus.time, evaluatePoint(postPoint(beforePlus.keyframe), evaluator, context, path)[axis]));
  }
  points.push(new Vector2(before.time, evaluatePoint(postPoint(before.keyframe), evaluator, context, path)[axis]));
  points.push(new Vector2(after.time, evaluatePoint(prePoint(after.keyframe), evaluator, context, path)[axis]));
  if (afterPlus && after.keyframe.data_points.length === 1) {
    points.push(new Vector2(afterPlus.time, evaluatePoint(prePoint(afterPlus.keyframe), evaluator, context, path)[axis]));
  }
  const curveTime = (alpha + (beforePlus ? 1 : 0)) / (points.length - 1);
  return new SplineCurve(points).getPoint(curveTime).y;
}

function bezier(before: TimedKeyframe, after: TimedKeyframe, start: number, end: number, axis: number, alpha: number): number {
  const gap = after.time - before.time;
  const rightTime = clamp(before.keyframe.bezier_right_time?.[axis] ?? 0.1, 0, gap);
  const leftTime = clamp(after.keyframe.bezier_left_time?.[axis] ?? -0.1, -gap, 0);
  const curve = new CubicBezierCurve(
    new Vector2(before.time, start),
    new Vector2(before.time + rightTime, start + (before.keyframe.bezier_right_value?.[axis] ?? 0)),
    new Vector2(after.time + leftTime, end + (after.keyframe.bezier_left_value?.[axis] ?? 0)),
    new Vector2(after.time, end),
  );
  const targetTime = before.time + gap * alpha;
  let low = 0;
  let high = 1;
  for (let iteration = 0; iteration < 24; iteration++) {
    const middle = (low + high) / 2;
    if (curve.getPoint(middle).x < targetTime) low = middle;
    else high = middle;
  }
  return curve.getPoint((low + high) / 2).y;
}

function prePoint(keyframe: BbKeyframe): BbDataPoint {
  return requiredPoint(keyframe, 0);
}

function postPoint(keyframe: BbKeyframe): BbDataPoint {
  return requiredPoint(keyframe, keyframe.data_points.length - 1);
}

function requiredPoint(keyframe: BbKeyframe, index: number): BbDataPoint {
  if (keyframe.data_points.length < 1 || keyframe.data_points.length > 2) {
    throw new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair.");
  }
  return keyframe.data_points[index];
}

function evaluatePoint(point: BbDataPoint, evaluator: MolangBakeEvaluator, context: MolangContext, path: string): Vector3Tuple {
  if (point.x === undefined || point.y === undefined || point.z === undefined) {
    throw new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value.", path);
  }
  return [point.x, point.y, point.z].map((value) => evaluator.evaluate(value, { ...context, lifeTime: context.animationTime }, path)) as Vector3Tuple;
}

function createMolangEvaluator(): MolangBakeEvaluator {
  return new MolangBakeEvaluator({
    error: {
      code: "unsupported_geckolib_molang",
      message: (expression) => `GeckoLib expression ${expression} cannot be baked.`,
    },
  });
}

function mapAxes(mapper: (axis: number) => number): Vector3Tuple {
  return [mapper(0), mapper(1), mapper(2)];
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}
