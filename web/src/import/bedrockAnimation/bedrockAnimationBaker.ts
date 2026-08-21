import type {
  BedrockAnimation,
  BedrockChannel,
  BedrockExpression,
  BedrockKeyframe,
  BedrockKeyframeValue,
  BedrockVector,
} from "./bedrockAnimationSchema";
import { TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import { PREVIEW_PLAYER_STATE_QUERIES } from "../runtimeMolangQueries";

interface ResolvedKeyframe {
  time: number;
  pre: BedrockVector;
  post: BedrockVector;
  lerpMode: "linear" | "catmullrom";
  discontinuous: boolean;
}

const RESOLVED_KEYFRAMES = new WeakMap<Record<string, BedrockKeyframe>, readonly ResolvedKeyframe[]>();

export interface BedrockSamplePlan {
  sourceTimes: Map<number, number>;
  stepTicks: Set<number>;
}

interface Anchor {
  time: number;
  priority: number;
  step: boolean;
}

interface PlanState {
  lastTick: number;
  priority: number;
  count: number;
  distance: number;
  previous?: PlanState;
  assignment?: { anchor: Anchor; tick: number };
}

export function bedrockAnimationDurationSeconds(animation: BedrockAnimation): number {
  let duration = animation.animation_length ?? 0;
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (!isKeyframedChannel(channel)) continue;
      for (const timestamp of Object.keys(channel)) duration = Math.max(duration, Number(timestamp));
    }
  }
  return duration;
}

export function planBedrockAnimationSamples(animation: BedrockAnimation, durationTicks: number, playbackRate: number): BedrockSamplePlan {
  const anchors = collectAnchors(animation)
    .map((anchor) => ({ ...anchor, playbackTime: anchor.time / playbackRate }))
    .filter((anchor) => anchor.playbackTime * TICKS_PER_SECOND <= durationTicks + 1e-9);
  let states = new Map<number, PlanState>([[-1, { lastTick: -1, priority: 0, count: 0, distance: 0 }]]);
  for (const anchor of anchors) {
    const scaled = anchor.playbackTime * TICKS_PER_SECOND;
    const candidates = [...new Set([Math.floor(scaled), Math.ceil(scaled)])].filter((tick) => tick >= 0 && tick <= durationTicks);
    const next = new Map<number, PlanState>();
    for (const state of states.values()) {
      retainBetter(next, state);
      for (const tick of candidates) {
        if (tick <= state.lastTick) continue;
        retainBetter(next, {
          lastTick: tick,
          priority: state.priority + anchor.priority,
          count: state.count + 1,
          distance: state.distance + Math.abs(tick / TICKS_PER_SECOND - anchor.playbackTime),
          previous: state,
          assignment: { anchor, tick },
        });
      }
    }
    states = next;
  }

  const best = [...states.values()].reduce((current, candidate) => betterThan(candidate, current) ? candidate : current);
  const sourceTimes = new Map<number, number>();
  const stepTicks = new Set<number>();
  for (let state: PlanState | undefined = best; state?.assignment; state = state.previous) {
    sourceTimes.set(state.assignment.tick, state.assignment.anchor.time);
    if (state.assignment.anchor.step) stepTicks.add(state.assignment.tick);
  }
  return { sourceTimes, stepTicks };
}

export function evaluateBedrockChannel(channel: BedrockChannel | undefined, time: number, fallback: number[], path: string): number[] {
  if (channel === undefined) return [...fallback];
  if (!isKeyframedChannel(channel)) return evaluateVector(channel, path, time, 1);
  const frames = resolveKeyframes(channel);
  const exact = frames.find((frame) => Math.abs(frame.time - time) < 1e-9);
  if (exact) return evaluateVector(exact.post, `${path}.${exact.time}.post`, time, 1);
  const afterIndex = frames.findIndex((frame) => frame.time > time);
  if (afterIndex === 0) return [...fallback];
  if (afterIndex < 0) return evaluateVector(frames[frames.length - 1].post, `${path}.${frames[frames.length - 1].time}.post`, time, 1);

  const before = frames[afterIndex - 1];
  const after = frames[afterIndex];
  const alpha = (time - before.time) / (after.time - before.time);
  const start = evaluateVector(before.post, `${path}.${before.time}.post`, time, alpha);
  const end = evaluateVector(after.pre, `${path}.${after.time}.pre`, time, alpha);
  if (before.lerpMode === "catmullrom" || after.lerpMode === "catmullrom") {
    const previous = frames[afterIndex - 2];
    const following = frames[afterIndex + 1];
    const p0 = previous ? evaluateVector(previous.post, `${path}.${previous.time}.post`, time, alpha) : start;
    const p3 = following ? evaluateVector(following.pre, `${path}.${following.time}.pre`, time, alpha) : end;
    return start.map((value, axis) => catmullRom(p0[axis], value, end[axis], p3[axis], alpha));
  }
  return start.map((value, axis) => value + (end[axis] - value) * alpha);
}

export function bedrockAnimationPlaybackRate(animation: BedrockAnimation, path: string): number {
  if (animation.anim_time_update === undefined) return 1;
  const delta = 1 / TICKS_PER_SECOND;
  const fromZero = evaluateBedrockExpression(animation.anim_time_update, 0, 1, `${path}.anim_time_update`);
  const fromOne = evaluateBedrockExpression(animation.anim_time_update, 1, 1, `${path}.anim_time_update`);
  const rate = fromZero / delta;
  if (!Number.isFinite(rate) || rate <= 0 || Math.abs((fromOne - 1) - fromZero) > 1e-7) {
    throw new ConversionError(
      "unsupported_bedrock_molang",
      `${path}.anim_time_update must advance q.anim_time at a constant positive rate.`,
      `${path}.anim_time_update`,
    );
  }
  return rate;
}

export function bedrockAnimationUsesTime(animation: BedrockAnimation): boolean {
  return Object.values(animation.bones ?? {}).some((bone) => [bone.position, bone.rotation, bone.scale]
    .some((channel) => channelExpressions(channel).some((expression) => typeof expression === "string" && /(?:q|query)\.anim_time\b/i.test(expression))));
}

export function evaluateBedrockExpression(expression: BedrockExpression, animationTime: number, keyframeLerpTime: number, path: string): number {
  if (typeof expression === "number") return expression;
  const numeric = Number(expression.trim());
  if (Number.isFinite(numeric)) return numeric;
  if (/math\.(?:random|random_integer|die_roll|die_roll_integer)\b/i.test(expression)) {
    throw new ConversionError("unsupported_bedrock_molang", `${path} uses nondeterministic Molang and cannot be baked.`, path);
  }
  const parser = new MolangParser();
  parser.variableHandler = (key) => {
    throw new Error(`${path} references runtime Molang variable ${key}.`);
  };
  try {
    const result = parser.parse(expression, {
      ...PREVIEW_PLAYER_STATE_QUERIES,
      "query.anim_time": animationTime,
      "q.anim_time": animationTime,
      "query.delta_time": 1 / TICKS_PER_SECOND,
      "q.delta_time": 1 / TICKS_PER_SECOND,
      "query.key_frame_lerp_time": keyframeLerpTime,
      "q.key_frame_lerp_time": keyframeLerpTime,
      "global.key_frame_lerp_time": keyframeLerpTime,
    });
    if (!Number.isFinite(result)) throw new Error("result is not finite");
    return result;
  } catch (error) {
    throw new ConversionError("unsupported_bedrock_molang", `${path} contains Molang that cannot be baked.`, path, { cause: error });
  }
}

function collectAnchors(animation: BedrockAnimation): Anchor[] {
  const anchors = new Map<string, Anchor>();
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (!isKeyframedChannel(channel)) continue;
      for (const frame of resolveKeyframes(channel)) {
        const key = frame.time.toFixed(9);
        const current = anchors.get(key);
        const priority = frame.discontinuous ? 200 : 100;
        if (!current) anchors.set(key, { time: frame.time, priority, step: frame.discontinuous });
        else {
          current.priority = Math.max(current.priority, priority);
          current.step ||= frame.discontinuous;
        }
      }
    }
  }
  return [...anchors.values()].sort((first, second) => first.time - second.time);
}

function resolveKeyframes(channel: Record<string, BedrockKeyframe>): readonly ResolvedKeyframe[] {
  const cached = RESOLVED_KEYFRAMES.get(channel);
  if (cached) return cached;

  const frames = Object.entries(channel).map<ResolvedKeyframe>(([timestamp, value]) => {
    if (!isKeyframeValue(value)) {
      return { time: Number(timestamp), pre: value, post: value, lerpMode: "linear", discontinuous: false };
    }
    const pre = value.pre ?? value.post;
    const post = value.post ?? value.pre;
    if (pre === undefined || post === undefined) throw new Error(`Bedrock keyframe ${timestamp} is missing pre and post values.`);
    return {
      time: Number(timestamp),
      pre,
      post,
      lerpMode: value.lerp_mode ?? "linear",
      discontinuous: JSON.stringify(pre) !== JSON.stringify(post),
    };
  }).sort((first, second) => first.time - second.time);
  RESOLVED_KEYFRAMES.set(channel, frames);
  return frames;
}

function evaluateVector(vector: BedrockVector, path: string, animationTime: number, keyframeLerpTime: number): number[] {
  const values = Array.isArray(vector) ? vector : [vector];
  if (values.length === 1) {
    const value = evaluateBedrockExpression(values[0], animationTime, keyframeLerpTime, `${path}[0]`);
    return [value, value, value];
  }
  return values.map((value, axis) => evaluateBedrockExpression(value, animationTime, keyframeLerpTime, `${path}[${axis}]`));
}

function channelExpressions(channel: BedrockChannel | undefined): BedrockExpression[] {
  if (channel === undefined) return [];
  if (!isKeyframedChannel(channel)) return Array.isArray(channel) ? channel : [channel];
  return Object.values(channel).flatMap((keyframe) => {
    if (!isKeyframeValue(keyframe)) return Array.isArray(keyframe) ? keyframe : [keyframe];
    return [keyframe.pre, keyframe.post].flatMap((vector) => vector === undefined ? [] : Array.isArray(vector) ? vector : [vector]);
  });
}

function isKeyframedChannel(channel: BedrockChannel | undefined): channel is Record<string, BedrockKeyframe> {
  return typeof channel === "object" && channel !== null && !Array.isArray(channel);
}

function isKeyframeValue(value: BedrockKeyframe): value is BedrockKeyframeValue {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function catmullRom(p0: number, p1: number, p2: number, p3: number, alpha: number): number {
  const alpha2 = alpha * alpha;
  const alpha3 = alpha2 * alpha;
  return 0.5 * ((2 * p1) + (-p0 + p2) * alpha + (2 * p0 - 5 * p1 + 4 * p2 - p3) * alpha2 + (-p0 + 3 * p1 - 3 * p2 + p3) * alpha3);
}

function retainBetter(states: Map<number, PlanState>, candidate: PlanState): void {
  const current = states.get(candidate.lastTick);
  if (!current || betterThan(candidate, current)) states.set(candidate.lastTick, candidate);
}

function betterThan(candidate: PlanState, current: PlanState): boolean {
  if (candidate.priority !== current.priority) return candidate.priority > current.priority;
  if (candidate.count !== current.count) return candidate.count > current.count;
  return candidate.distance < current.distance;
}
import MolangParser from "molangjs/dist/molang.esm.js";
