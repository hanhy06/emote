import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { Matrix16 } from "../../domain/matrix";
import { ConversionError } from "../../foundation/diagnostics";
import { matrix4ToRowMajor } from "../../format/matrix";
import { usesRuntimeMolangState } from "../../format/molang/runtimeAnalysis";
import { easingProgress } from "../common/curveMath";
import { planAnimationAnchorSamples, type AnimationAnchor } from "../common/animationSampling";
import { MolangBakeEvaluator } from "../common/molangBakeEvaluator";
import type { PalAnimation, PalAxisChannels, PalExpression, PalKeyframe } from "./emotecraftBinary";
import {
  EMOTECRAFT_PIVOTS,
  EMOTECRAFT_PLAYER_PARTS,
  EMOTECRAFT_RENDER_SCALE,
  createEmotecraftSlices,
  type EmotecraftSlice,
} from "./emotecraftPlayerRig";

type Vector3Tuple = [number, number, number];
type ChannelKind = "position" | "rotation" | "scale";
interface BonePose { position: Vector3Tuple; rotation: Vector3Tuple; scale: Vector3Tuple; bend: number }

export interface EmotecraftSampleFrame {
  tick: number;
  matrix: Matrix16;
  step: boolean;
}

export interface EmotecraftAnimationSamples {
  durationTicks: number;
  slices: EmotecraftSlice[];
  bindMatrices: ReadonlyMap<string, Matrix4>;
  transforms: Readonly<Record<string, readonly EmotecraftSampleFrame[]>>;
}

const DEFAULT_POSE: BonePose = { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], bend: 0 };
const ZERO_PIVOT = [0, 0, 0] as const;
const CHANNEL_DEFAULTS: Readonly<Record<ChannelKind, Vector3Tuple>> = {
  position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1],
};
const EVALUATOR = new MolangBakeEvaluator({
  rejectNondeterministic: true,
  error: {
    code: "unsupported_emotecraft_molang",
    message: (expression, path) => `${path} uses Emotecraft MoLang that cannot be baked: ${expression}`,
    nondeterministicMessage: (expression, path) => `${path} uses nondeterministic Emotecraft MoLang: ${expression}`,
  },
});

export function sampleEmotecraftAnimation(animation: PalAnimation, displayName: string, durationTicks: number): EmotecraftAnimationSamples {
  const bentBones = new Set(EMOTECRAFT_PLAYER_PARTS.filter((part) => animation.bones[part.bone]?.bend.length).map((part) => part.bone));
  const slices = createEmotecraftSlices(bentBones);
  const snapshotAt = (time: number) => {
    const poses = evaluatePoses(animation, time * 20);
    const matrices = buildSliceMatrices(animation, slices, poses);
    return slices.map((slice) => matrices.get(slice.id)!);
  };
  const samplePlan = planAnimationAnchorSamples(collectAnimationAnchors(animation), durationTicks, slices.length, snapshotAt);
  const transforms = Object.fromEntries(slices.map((slice) => [slice.id, [] as EmotecraftSampleFrame[]]));
  let bindMatrices: Map<string, Matrix4> | undefined;
  for (let tick = 0; tick <= durationTicks; tick++) {
    const sourceTick = (samplePlan.sourceTimes.get(tick) ?? tick / 20) * 20;
    const poses = evaluatePoses(animation, sourceTick);
    const matrices = buildSliceMatrices(animation, slices, poses);
    bindMatrices ??= matrices;
    for (const slice of slices) transforms[slice.id].push({
      tick,
      matrix: matrix4ToRowMajor(matrices.get(slice.id)!, `${displayName}/${slice.id}/${tick}`),
      step: tick === 0 || samplePlan.stepTicks.has(tick),
    });
  }
  return { durationTicks, slices, bindMatrices: bindMatrices!, transforms };
}

function collectAnimationAnchors(animation: PalAnimation): AnimationAnchor[] {
  const anchors = new Map<string, AnimationAnchor>();
  for (const bone of Object.values(animation.bones)) {
    for (const frames of [...bone.position, ...bone.rotation, ...bone.scale, bone.bend]) {
      for (const frame of frames) {
        if (!Number.isFinite(frame.endTick) || frame.endTick < 0 || frame.endTick > animation.lengthTicks) continue;
        const time = frame.endTick / 20;
        const key = time.toFixed(6);
        const step = frame.easing === "constant";
        const current = anchors.get(key);
        if (!current) anchors.set(key, { time, priority: step ? 200 : 100, step });
        else {
          current.priority = Math.max(current.priority, step ? 200 : 100);
          current.step ||= step;
        }
      }
    }
  }
  return [...anchors.values()].sort((first, second) => first.time - second.time);
}

function evaluatePoses(animation: PalAnimation, tick: number): Map<string, BonePose> {
  return new Map(Object.entries(animation.bones).map(([name, bone]) => [name, {
    position: evaluateAxes(name, bone.position, tick, animation, "position"),
    rotation: evaluateAxes(name, bone.rotation, tick, animation, "rotation"),
    scale: evaluateAxes(name, bone.scale, tick, animation, "scale"),
    bend: evaluateChannel(bone.bend, tick, animation, `bones.${name}.bend`, 0, true),
  }]));
}

function evaluateAxes(name: string, channels: PalAxisChannels, tick: number, animation: PalAnimation, type: ChannelKind): Vector3Tuple {
  const defaults = CHANNEL_DEFAULTS[type];
  const evaluateAxis = (axis: 0 | 1 | 2) => evaluateChannel(
    channels[axis], tick, animation, `bones.${name}.${type}.${axis}`, defaults[axis], type === "rotation",
  );
  return [evaluateAxis(0), evaluateAxis(1), evaluateAxis(2)];
}

function evaluateChannel(frames: readonly PalKeyframe[], tick: number, animation: PalAnimation, path: string, defaultValue: number, angular: boolean): number {
  if (frames.length === 0) return defaultValue;
  const frame = frames.find((candidate) => candidate.endTick > tick) ?? frames.at(-1)!;
  const rawProgress = frame.endTick > frame.startTick ? (tick - frame.startTick) / (frame.endTick - frame.startTick) : 0;
  const progress = Math.max(0, Math.min(1, rawProgress));
  const context = { animationTime: tick / 20, keyframeLerpTime: progress };
  const start = evaluateExpression(frame.start, context, `${path}.start`, angular);
  const end = evaluateExpression(frame.end, context, `${path}.end`, angular);
  const args = frame.easingArgs.map((argument) => argument.map((value) => evaluateExpression(value, context, `${path}.easing`, false)));
  let value = interpolateFrame(frame, start, end, progress, args);
  const begin = animation.beginTick;
  if (begin !== undefined && begin > 0 && tick < begin) value = defaultValue + (value - defaultValue) * easeInOutSine(tick / begin);
  const endTick = animation.endTick;
  if (endTick !== undefined && animation.lengthTicks > endTick && tick >= endTick) {
    value += (defaultValue - value) * easeInOutSine((tick - endTick) / (animation.lengthTicks - endTick));
  }
  return value;
}

function evaluateExpression(expression: PalExpression, context: { animationTime: number; keyframeLerpTime: number }, path: string, angular: boolean): number {
  if (usesRuntimeMolangState(expression)) {
    throw new ConversionError("unsupported_emotecraft_runtime_molang", `${path} uses player-dependent Emotecraft MoLang that cannot be represented by the output rig: ${expression}`, path);
  }
  const value = EVALUATOR.evaluate(expression, context, path);
  return angular && typeof expression === "string" ? value * Math.PI / 180 : value;
}

function interpolateFrame(frame: PalKeyframe, start: number, end: number, progress: number, args: number[][]): number {
  if (frame.easing === "constant") return progress >= 1 ? end : start;
  if (frame.easing === "catmullrom" && args.length >= 2) {
    const p0 = args[0][0] ?? start;
    const p3 = args[1][0] ?? end;
    return 0.5 * (2 * start + (end - p0) * progress + (2 * p0 - 5 * start + 4 * end - p3) * progress ** 2 + (3 * start - p0 - 3 * end + p3) * progress ** 3);
  }
  if (frame.easing === "bezier" && args.length >= 2) return bezierValue(start, end, progress, args, frame.endTick - frame.startTick);
  const eased = easingProgress(frame.easing, progress, args.map((entry) => entry[0] ?? 0)) ?? progress;
  return start + (end - start) * eased;
}

function bezierValue(start: number, end: number, progress: number, args: number[][], lengthTicks: number): number {
  const leftValue = args[0]?.[0] ?? 0;
  const leftTime = args[1]?.[0] ?? 0;
  const rightValue = args[2]?.[0] ?? 0;
  const rightTime = args[3]?.[0] ?? 0.1;
  const seconds = Math.max(lengthTicks / 20, Number.EPSILON);
  const x1 = Math.max(0, Math.min(1, rightTime / seconds));
  const x2 = Math.max(0, Math.min(1, 1 + leftTime / seconds));
  let bestT = 0;
  let bestDistance = Infinity;
  for (let index = 0; index <= 200; index++) {
    const t = index / 200;
    const x = cubic(t, 0, x1, x2, 1);
    const distance = Math.abs(x - progress);
    if (distance < bestDistance) { bestDistance = distance; bestT = t; }
  }
  return cubic(bestT, start, start + rightValue, end + leftValue, end);
}

function cubic(t: number, p0: number, p1: number, p2: number, p3: number): number {
  const inverse = 1 - t;
  return inverse ** 3 * p0 + 3 * inverse ** 2 * t * p1 + 3 * inverse * t ** 2 * p2 + t ** 3 * p3;
}

function easeInOutSine(progress: number): number {
  const clamped = Math.max(0, Math.min(1, progress));
  return -(Math.cos(Math.PI * clamped) - 1) / 2;
}

function buildSliceMatrices(animation: PalAnimation, slices: readonly EmotecraftSlice[], poses: ReadonlyMap<string, BonePose>): Map<string, Matrix4> {
  const root = new Matrix4().makeScale(EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE);
  const body = root.clone().multiply(localBoneMatrix("body", undefined, poses.get("body"), animation));
  const boneMatrices = new Map<string, Matrix4>([["body", body]]);
  const visiting = new Set<string>();
  const worldFor = (name: string): Matrix4 => {
    const cached = boneMatrices.get(name);
    if (cached) return cached;
    if (visiting.has(name)) throw new Error(`Emotecraft custom parent cycle includes ${name}.`);
    visiting.add(name);
    const customParent = animation.parents[name];
    const parentWorld = customParent ? worldFor(customParent) : body;
    const parentName = customParent ?? "body";
    const result = parentWorld.clone().multiply(localBoneMatrix(name, parentName, poses.get(name), animation));
    visiting.delete(name);
    boneMatrices.set(name, result);
    return result;
  };
  for (const name of new Set([...Object.keys(animation.bones), ...Object.keys(animation.pivots), ...EMOTECRAFT_PLAYER_PARTS.map((part) => part.bone)])) worldFor(name);
  const torsoBend = poses.get("torso")?.bend ?? 0;
  if (animation.applyBendToOtherBones && Math.abs(torsoBend) > 0.001) {
    const bend = pivotRotationMatrix([0, 6, 0], torsoBend);
    for (const name of ["head", "left_arm", "right_arm"]) boneMatrices.set(name, body.clone().multiply(bend).multiply(body.clone().invert()).multiply(worldFor(name)));
  }
  return new Map(slices.map((slice) => {
    const upper = boneMatrices.get(slice.source.bone)!;
    return [slice.id, slice.lower ? upper.clone().multiply(lowerBendMatrix(poses.get(slice.source.bone)?.bend ?? 0)) : upper];
  }));
}

function localBoneMatrix(name: string, parentName: string | undefined, pose: BonePose | undefined, animation: PalAnimation): Matrix4 {
  const pivot = animation.pivots[name] ?? EMOTECRAFT_PIVOTS[name] ?? ZERO_PIVOT;
  const parentPivot = parentName ? animation.pivots[parentName] ?? EMOTECRAFT_PIVOTS[parentName] ?? ZERO_PIVOT : ZERO_PIVOT;
  const value = pose ?? DEFAULT_POSE;
  return new Matrix4().compose(
    new Vector3(...palLocalPosition(pivot, parentPivot, value.position)),
    new Quaternion().setFromEuler(new Euler(...palRotation(value.rotation), "ZYX")),
    new Vector3(value.scale[0], value.scale[1], value.scale[2]),
  );
}

function palLocalPosition(pivot: readonly [number, number, number], parentPivot: readonly [number, number, number], position: Vector3Tuple): Vector3Tuple {
  return [((pivot[0] - parentPivot[0]) - position[0]) / 16, ((pivot[1] - parentPivot[1]) + position[1]) / 16, ((pivot[2] - parentPivot[2]) + position[2]) / 16];
}

function palRotation(rotation: Vector3Tuple): Vector3Tuple {
  return [-rotation[0], -rotation[1], rotation[2]];
}

function pivotRotationMatrix(pivot: readonly [number, number, number], bend: number): Matrix4 {
  const p = new Vector3(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
  return new Matrix4().makeTranslation(p.x, p.y, p.z)
    .multiply(new Matrix4().makeRotationX(-bend))
    .multiply(new Matrix4().makeTranslation(-p.x, -p.y, -p.z));
}

function lowerBendMatrix(bend: number): Matrix4 {
  const radius = 2 / 16;
  return new Matrix4().compose(
    new Vector3(0, -6 / 16 + radius * (1 - Math.cos(bend)), radius * Math.sin(bend)),
    new Quaternion().setFromEuler(new Euler(-bend, 0, 0, "ZYX")),
    new Vector3(1, 1, 1),
  );
}
