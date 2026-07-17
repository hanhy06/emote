import MolangParser from "molangjs/dist/molang.esm.js";
import { CubicBezierCurve, SplineCurve, Vector2 } from "three";
import { TICKS_PER_SECOND, secondsToTicks } from "../../format/time";
import type { AjKeyframe, AjNodeChannels } from "./animatedJavaSchema";

type Vector3Tuple = [number, number, number];

export interface AjTransformValues {
  position: Vector3Tuple;
  rotation: Vector3Tuple;
  scale: Vector3Tuple;
}

export interface BakedAjTransform extends AjTransformValues {
  tick: number;
  time: number;
}

interface TimedKeyframe {
  time: number;
  keyframe: AjKeyframe;
}

interface MolangContext {
  animationTime: number;
  keyframeLerpTime: number;
}

export function requiresAjBaking(channels: AjNodeChannels, blendWeight: string): boolean {
  if (!isNumericExpression(blendWeight)) return true;
  return [channels.position, channels.rotation, channels.scale].some((channel) =>
    Object.values(channel ?? {}).some((keyframe) =>
      keyframe.post != null
      || keyframe.interpolation.type === "bezier"
      || keyframe.interpolation.type === "catmullrom"
      || (keyframe.interpolation.type === "linear" && !["linear", "step"].includes(keyframe.interpolation.easing))
      || [...keyframe.value, ...(keyframe.post ?? [])].some((value) => !isNumericExpression(value)),
    ),
  );
}

export function bakeAjNodeChannels(
  channels: AjNodeChannels,
  base: AjTransformValues,
  durationTicks: number,
  path: string,
): BakedAjTransform[] {
  const position = prepareChannel(channels.position, `${path}/position`);
  const rotation = prepareChannel(channels.rotation, `${path}/rotation`);
  const scale = prepareChannel(channels.scale, `${path}/scale`);
  const parser = createMolangParser(path);
  const frames: BakedAjTransform[] = [];
  for (let tick = 0; tick <= durationTicks; tick++) {
    const time = tick / TICKS_PER_SECOND;
    frames.push({
      tick,
      time,
      position: evaluateChannel(position, base.position, time, parser),
      rotation: evaluateChannel(rotation, base.rotation, time, parser),
      scale: evaluateChannel(scale, base.scale, time, parser),
    });
  }
  return frames;
}

export function evaluateAjMolang(expression: string, context: MolangContext, path: string): number {
  if (isNumericExpression(expression)) return Number(expression);
  return parseMolang(createMolangParser(path), expression, context, path);
}

function prepareChannel(channel: Record<string, AjKeyframe> | undefined, path: string): TimedKeyframe[] {
  return Object.entries(channel ?? {}).map(([key, keyframe]) => {
    const time = Number(key);
    if (!Number.isFinite(time) || time < 0) throw new Error(`${path} has invalid keyframe time ${key}.`);
    secondsToTicks(time, `${path} keyframe`);
    return { time, keyframe };
  }).sort((first, second) => first.time - second.time);
}

function evaluateChannel(
  frames: TimedKeyframe[],
  fallback: Vector3Tuple,
  time: number,
  parser: MolangParser,
): Vector3Tuple {
  if (frames.length === 0) return [...fallback];
  const exact = frames.find((frame) => Math.abs(frame.time - time) < 1e-9);
  if (exact) return evaluateVector(exact.keyframe.value, parser, { animationTime: time, keyframeLerpTime: 0 });
  const afterIndex = frames.findIndex((frame) => frame.time > time);
  if (afterIndex === 0) return evaluateVector(frames[0].keyframe.value, parser, { animationTime: time, keyframeLerpTime: 0 });
  if (afterIndex < 0) {
    const last = frames[frames.length - 1].keyframe;
    return evaluateVector(last.post ?? last.value, parser, { animationTime: time, keyframeLerpTime: 1 });
  }
  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const alpha = (time - before.time) / (after.time - before.time);
  const context = { animationTime: time, keyframeLerpTime: alpha };
  if (before.keyframe.interpolation.type === "step") {
    return evaluateVector(before.keyframe.post ?? before.keyframe.value, parser, context);
  }
  const start = evaluateVector(before.keyframe.post ?? before.keyframe.value, parser, context);
  const end = evaluateVector(after.keyframe.value, parser, context);
  if (before.keyframe.interpolation.type === "catmullrom" || after.keyframe.interpolation.type === "catmullrom") {
    return mapAxes((axis) => catmullRom(frames, afterIndex, axis, alpha, parser, context));
  }
  if (before.keyframe.interpolation.type === "bezier" || after.keyframe.interpolation.type === "bezier") {
    return mapAxes((axis) => bezier(before, after, start[axis], end[axis], axis, alpha));
  }
  const eased = after.keyframe.interpolation.type === "linear"
    ? easing(after.keyframe.interpolation.easing, after.keyframe.interpolation.easing_arguments, alpha)
    : alpha;
  return mapAxes((axis) => start[axis] + (end[axis] - start[axis]) * eased);
}

function catmullRom(
  frames: TimedKeyframe[],
  afterIndex: number,
  axis: number,
  alpha: number,
  parser: MolangParser,
  context: MolangContext,
): number {
  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const beforePlus = frames[afterIndex - 2];
  const afterPlus = frames[afterIndex + 1];
  const points: Vector2[] = [];
  if (beforePlus && before.keyframe.post == null) points.push(new Vector2(beforePlus.time, evaluateVector(beforePlus.keyframe.post ?? beforePlus.keyframe.value, parser, context)[axis]));
  points.push(new Vector2(before.time, evaluateVector(before.keyframe.post ?? before.keyframe.value, parser, context)[axis]));
  points.push(new Vector2(after.time, evaluateVector(after.keyframe.value, parser, context)[axis]));
  if (afterPlus && after.keyframe.post == null) points.push(new Vector2(afterPlus.time, evaluateVector(afterPlus.keyframe.value, parser, context)[axis]));
  const curveTime = (alpha + (beforePlus ? 1 : 0)) / (points.length - 1);
  return new SplineCurve(points).getPoint(curveTime).y;
}

function bezier(before: TimedKeyframe, after: TimedKeyframe, start: number, end: number, axis: number, alpha: number): number {
  const gap = after.time - before.time;
  const beforeInterpolation = before.keyframe.interpolation.type === "bezier" ? before.keyframe.interpolation : undefined;
  const afterInterpolation = after.keyframe.interpolation.type === "bezier" ? after.keyframe.interpolation : undefined;
  const rightTime = clamp(beforeInterpolation?.right_handle_time[axis] ?? 0, 0, gap);
  const leftTime = clamp(afterInterpolation?.left_handle_time[axis] ?? 0, -gap, 0);
  const curve = new CubicBezierCurve(
    new Vector2(before.time, start),
    new Vector2(before.time + rightTime, start + (beforeInterpolation?.right_handle_value[axis] ?? 0)),
    new Vector2(after.time + leftTime, end + (afterInterpolation?.left_handle_value[axis] ?? 0)),
    new Vector2(after.time, end),
  );
  const targetTime = before.time + gap * alpha;
  const points = curve.getPoints(200);
  points.sort((first, second) => Math.abs(first.x - targetTime) - Math.abs(second.x - targetTime));
  const [first, second] = points;
  if (!second || Math.abs(second.x - first.x) < 1e-9) return first.y;
  const progress = clamp((targetTime - first.x) / (second.x - first.x), 0, 1);
  return first.y + (second.y - first.y) * progress;
}

function evaluateVector(values: string[], parser: MolangParser, context: MolangContext): Vector3Tuple {
  if (values.length !== 3) throw new Error("Animated Java transform vector must contain three values.");
  return values.map((value) => parseMolang(parser, value, context, "Animated Java transform")) as Vector3Tuple;
}

function parseMolang(parser: MolangParser, expression: string, context: MolangContext, path: string): number {
  try {
    const value = parser.parse(expression, {
      "query.anim_time": context.animationTime,
      "q.anim_time": context.animationTime,
      "query.life_time": context.animationTime,
      "q.life_time": context.animationTime,
      "query.key_frame_lerp_time": context.keyframeLerpTime,
      "q.key_frame_lerp_time": context.keyframeLerpTime,
      "global.key_frame_lerp_time": context.keyframeLerpTime,
      "query.delta_time": 1 / TICKS_PER_SECOND,
      "q.delta_time": 1 / TICKS_PER_SECOND,
    });
    if (!Number.isFinite(value)) throw new Error("result is not finite");
    return value;
  } catch (error) {
    throw new Error(`${path} contains a Molang expression that cannot be baked: ${expression}`, { cause: error });
  }
}

function createMolangParser(path: string): MolangParser {
  const parser = new MolangParser();
  parser.variableHandler = (key) => {
    throw new Error(`${path} references runtime Molang variable ${key}`);
  };
  return parser;
}

function easing(name: string, args: number[] | undefined, value: number): number {
  const normalized = name.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase());
  if (normalized === "linear") return value;
  if (normalized === "step") {
    const steps = Math.max(2, Math.floor(args?.[0] ?? 5));
    return Math.floor(value * steps) / steps;
  }
  const match = /^ease(InOut|In|Out)(Quad|Cubic|Quart|Quint|Sine|Expo|Circ|Back|Elastic|Bounce)$/.exec(normalized);
  if (!match) throw new Error(`Unsupported Animated Java easing: ${name}`);
  const direction = match[1];
  const kind = match[2];
  const base = (time: number) => baseEasing(kind, args?.[0], time);
  if (direction === "In") return base(value);
  if (direction === "Out") return 1 - base(1 - value);
  return value < 0.5 ? base(value * 2) / 2 : 1 - base((1 - value) * 2) / 2;
}

function baseEasing(kind: string, argument: number | undefined, value: number): number {
  if (kind === "Quad") return value ** 2;
  if (kind === "Cubic") return value ** 3;
  if (kind === "Quart") return value ** 4;
  if (kind === "Quint") return value ** 5;
  if (kind === "Sine") return 1 - Math.cos(value * Math.PI / 2);
  if (kind === "Expo") return 2 ** (10 * (value - 1));
  if (kind === "Circ") return 1 - Math.sqrt(1 - value ** 2);
  if (kind === "Back") {
    const overshoot = 1.70158 * (argument ?? 1);
    return value ** 2 * ((overshoot + 1) * value - overshoot);
  }
  if (kind === "Elastic") return 1 - Math.cos(value * Math.PI / 2) ** 3 * Math.cos(value * (argument ?? 1) * Math.PI);
  const bounce = argument ?? 0.25;
  return Math.min(
    121 / 16 * value ** 2,
    121 / 4 * bounce * (value - 6 / 11) ** 2 + 1 - bounce,
    121 * bounce ** 2 * (value - 9 / 11) ** 2 + 1 - bounce ** 2,
    484 * bounce ** 3 * (value - 10.5 / 11) ** 2 + 1 - bounce ** 3,
  );
}

function isNumericExpression(value: string): boolean {
  return /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim());
}

function mapAxes(mapper: (axis: number) => number): Vector3Tuple {
  return [mapper(0), mapper(1), mapper(2)];
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}
