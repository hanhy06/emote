import MolangParser from "molangjs/dist/molang.esm.js";
import { TICKS_PER_SECOND, secondsToTicks } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
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

interface CompiledScalar {
  constant?: number;
  expression?: string;
}

type CompiledVector = [CompiledScalar, CompiledScalar, CompiledScalar];

interface CompiledKeyframe {
  time: number;
  interpolation: AjKeyframe["interpolation"];
  value: CompiledVector;
  post?: CompiledVector;
}

interface CompiledChannel {
  frames: CompiledKeyframe[];
  cursor: number;
  path: string;
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
  const position = compileChannel(channels.position, `${path}/position`);
  const rotation = compileChannel(channels.rotation, `${path}/rotation`);
  const scale = compileChannel(channels.scale, `${path}/scale`);
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

function compileChannel(channel: Record<string, AjKeyframe> | undefined, path: string): CompiledChannel {
  const frames = Object.entries(channel ?? {}).map<CompiledKeyframe>(([key, keyframe]) => {
    const time = Number(key);
    if (!Number.isFinite(time) || time < 0) throw new Error(`${path} has invalid keyframe time ${key}.`);
    secondsToTicks(time, `${path} keyframe`);
    return {
      time,
      interpolation: keyframe.interpolation,
      value: compileVector(keyframe.value),
      ...(keyframe.post ? { post: compileVector(keyframe.post) } : {}),
    };
  }).sort((first, second) => first.time - second.time);
  return { frames, cursor: 0, path };
}

function evaluateChannel(
  channel: CompiledChannel,
  fallback: Vector3Tuple,
  time: number,
  parser: MolangParser,
): Vector3Tuple {
  const frames = channel.frames;
  if (frames.length === 0) return [...fallback];
  while (channel.cursor + 1 < frames.length && frames[channel.cursor + 1].time <= time + 1e-9) {
    channel.cursor++;
  }
  const current = frames[channel.cursor];
  if (Math.abs(current.time - time) < 1e-9) {
    return evaluateVector(current.value, parser, { animationTime: time, keyframeLerpTime: 0 }, channel.path);
  }
  if (time < frames[0].time) {
    return evaluateVector(frames[0].value, parser, { animationTime: time, keyframeLerpTime: 0 }, channel.path);
  }
  if (channel.cursor + 1 >= frames.length) {
    return evaluateVector(current.post ?? current.value, parser, { animationTime: time, keyframeLerpTime: 1 }, channel.path);
  }
  const beforeIndex = channel.cursor;
  const before = frames[beforeIndex];
  const after = frames[beforeIndex + 1];
  const alpha = (time - before.time) / (after.time - before.time);
  const context = { animationTime: time, keyframeLerpTime: alpha };
  if (before.interpolation.type === "step") {
    return evaluateVector(before.post ?? before.value, parser, context, channel.path);
  }
  const start = evaluateVector(before.post ?? before.value, parser, context, channel.path);
  const end = evaluateVector(after.value, parser, context, channel.path);
  if (before.interpolation.type === "catmullrom" || after.interpolation.type === "catmullrom") {
    return catmullRom(frames, beforeIndex, start, end, alpha, parser, context, channel.path);
  }
  if (before.interpolation.type === "bezier" || after.interpolation.type === "bezier") {
    return mapAxes((axis) => bezier(before, after, start[axis], end[axis], axis, alpha));
  }
  const eased = after.interpolation.type === "linear"
    ? easing(after.interpolation.easing, after.interpolation.easing_arguments, alpha)
    : alpha;
  return mapAxes((axis) => start[axis] + (end[axis] - start[axis]) * eased);
}

function catmullRom(
  frames: CompiledKeyframe[],
  beforeIndex: number,
  start: Vector3Tuple,
  end: Vector3Tuple,
  alpha: number,
  parser: MolangParser,
  context: MolangContext,
  path: string,
): Vector3Tuple {
  const before = frames[beforeIndex];
  const after = frames[beforeIndex + 1];
  const previousFrame = frames[beforeIndex - 1];
  const followingFrame = frames[beforeIndex + 2];
  const previous = previousFrame && before.post == null
    ? evaluateVector(previousFrame.post ?? previousFrame.value, parser, context, path)
    : start;
  const following = followingFrame && after.post == null
    ? evaluateVector(followingFrame.value, parser, context, path)
    : end;
  return mapAxes((axis) => catmullRomScalar(previous[axis], start[axis], end[axis], following[axis], alpha));
}

function catmullRomScalar(p0: number, p1: number, p2: number, p3: number, alpha: number): number {
  const v0 = (p2 - p0) * 0.5;
  const v1 = (p3 - p1) * 0.5;
  const squared = alpha * alpha;
  const cubed = squared * alpha;
  return (2 * p1 - 2 * p2 + v0 + v1) * cubed
    + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * squared
    + v0 * alpha
    + p1;
}

function bezier(before: CompiledKeyframe, after: CompiledKeyframe, start: number, end: number, axis: number, alpha: number): number {
  const gap = after.time - before.time;
  const beforeInterpolation = before.interpolation.type === "bezier" ? before.interpolation : undefined;
  const afterInterpolation = after.interpolation.type === "bezier" ? after.interpolation : undefined;
  const rightTime = clamp(beforeInterpolation?.right_handle_time[axis] ?? 0, 0, gap);
  const leftTime = clamp(afterInterpolation?.left_handle_time[axis] ?? 0, -gap, 0);
  const x1 = gap === 0 ? 0 : rightTime / gap;
  const x2 = gap === 0 ? 1 : (gap + leftTime) / gap;
  const curveTime = invertBezierTime(x1, x2, alpha);
  return cubicBezier(start, start + (beforeInterpolation?.right_handle_value[axis] ?? 0), end + (afterInterpolation?.left_handle_value[axis] ?? 0), end, curveTime);
}

function invertBezierTime(x1: number, x2: number, target: number): number {
  if (target <= 0) return 0;
  if (target >= 1) return 1;

  const boundaries = [0, ...bezierDerivativeRoots(x1, x2), 1];
  let closest = 0;
  let closestDistance = target;
  for (let index = 0; index < boundaries.length - 1; index++) {
    let low = boundaries[index];
    let high = boundaries[index + 1];
    const lowValue = cubicBezier(0, x1, x2, 1, low);
    const highValue = cubicBezier(0, x1, x2, 1, high);
    const lowDistance = Math.abs(lowValue - target);
    if (lowDistance < closestDistance) {
      closest = low;
      closestDistance = lowDistance;
    }
    if (target < Math.min(lowValue, highValue) - 1e-12 || target > Math.max(lowValue, highValue) + 1e-12) continue;

    const increasing = highValue >= lowValue;
    for (let iteration = 0; iteration < 32; iteration++) {
      const middle = (low + high) / 2;
      const value = cubicBezier(0, x1, x2, 1, middle);
      if (increasing ? value < target : value > target) low = middle;
      else high = middle;
    }
    return (low + high) / 2;
  }
  return closest;
}

function bezierDerivativeRoots(x1: number, x2: number): number[] {
  const a = 3 * (3 * x1 - 3 * x2 + 1);
  const b = 6 * (x2 - 2 * x1);
  const c = 3 * x1;
  if (Math.abs(a) < 1e-12) {
    if (Math.abs(b) < 1e-12) return [];
    const root = -c / b;
    return root > 0 && root < 1 ? [root] : [];
  }
  const discriminant = b * b - 4 * a * c;
  if (discriminant <= 0) return [];
  const squareRoot = Math.sqrt(discriminant);
  return [(-b - squareRoot) / (2 * a), (-b + squareRoot) / (2 * a)]
    .filter((root) => root > 0 && root < 1)
    .sort((first, second) => first - second);
}

function cubicBezier(p0: number, p1: number, p2: number, p3: number, time: number): number {
  const inverse = 1 - time;
  return inverse * inverse * inverse * p0
    + 3 * inverse * inverse * time * p1
    + 3 * inverse * time * time * p2
    + time * time * time * p3;
}

function compileVector(values: string[]): CompiledVector {
  if (values.length !== 3) throw new Error("Animated Java transform vector must contain three values.");
  return values.map((value) => {
    const numeric = isNumericExpression(value) ? Number(value) : Number.NaN;
    return Number.isFinite(numeric) ? { constant: numeric } : { expression: value };
  }) as CompiledVector;
}

function evaluateVector(values: CompiledVector, parser: MolangParser, context: MolangContext, path: string): Vector3Tuple {
  return values.map((value) => value.constant ?? parseMolang(parser, value.expression!, context, path)) as Vector3Tuple;
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
    throw new ConversionError(
      "unsupported_animated_java_molang",
      `${path} contains a Molang expression that cannot be baked: ${expression}`,
      path,
      { cause: error },
    );
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
