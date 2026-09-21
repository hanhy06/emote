import type { AnimationRuntimeData } from "../../domain/runtimeProjection";
import type { ImportedNode } from "../../domain/conversionSeed";
import type { RuntimeNode, RuntimeNodeTracks, RuntimeVectorKeyframe } from "../../domain/minecraftData";
import { matrixToLocalTransform } from "../../format/localTransform";
import { matrix4ToRowMajor } from "../../format/matrix";
import { TICKS_PER_SECOND } from "../../format/time";
import type { BbAnimator, BbKeyframe } from "./blockbenchCubeSchema";
import { blockbenchEasingToEmote } from "./animationEasing";
import { affineMolang, isolateMolangAxis, molangScalar, negateMolang, type MolangVector } from "./molangVector";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "./runtimeOutput";
import type { CubeProjectTransformConvention } from "./blockbenchCubeTransform";
import { usesRuntimeMolangState } from "../../format/molang/runtimeAnalysis";
import type { BlockbenchChannelEvaluator } from "./blockbenchKeyframeEvaluator";
import type { BoneEntry } from "./blockbenchCubeModel";
import { blockbenchChannelSampling } from "./blockbenchAnimationSource";
import type { BlockbenchAnimationSource } from "./blockbenchAnimationSource";

export const PLAYER_RENDER_SCALE = 0.9375;
export interface BlockbenchNativeRuntimeContext {
  source: BlockbenchAnimationSource;
  bones: BoneEntry[];
  importedNodes: Record<string, ImportedNode>;
}

export type BlockbenchNativeRuntimeFactory = (
  context: BlockbenchNativeRuntimeContext,
) => Omit<Extract<AnimationRuntimeData, { kind: "native" }>, "kind">;

export interface BlockbenchNativeRuntimeOptions {
  formatLabel: string;
  runtimeSceneId: string;
  transforms: CubeProjectTransformConvention;
  channels: BlockbenchChannelEvaluator;
  invalidPointCount(): Error;
  missingAxis(): Error;
}

export function createBlockbenchNativeRuntime(options: BlockbenchNativeRuntimeOptions): BlockbenchNativeRuntimeFactory {
  return ({ source, bones, importedNodes }: BlockbenchNativeRuntimeContext) => {
    const { channels, formatLabel, transforms } = options;
    const { animators, blendWeight, durationTicks, startDelayTicks } = source;
    const sceneId = options.runtimeSceneId;
    const nodes: Record<string, RuntimeNode> = {
      [sceneId]: { type: "anchor", space: "initiator", transform: { ...IDENTITY_TRANSFORM, scale: [PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE] } },
    };
    const tracks: Record<string, RuntimeNodeTracks> = {};
    const editorNodeByRuntimeNode: Record<string, string> = {};
    for (const bone of bones) {
    const parent = bone.parent ? `${bone.parent.id}_x` : sceneId;
    const parentOrigin = bone.parent?.group.origin ?? ZERO_VECTOR;
    const basePosition = transforms.position(
      bone.group.origin.map((value, axis) => value - parentOrigin[axis]),
      (value) => -value,
    ).map((value) => value / 16) as [number, number, number];
    const baseRotation = transforms.rotation(bone.group.rotation, (value) => -value);
    nodes[`${bone.id}_z`] = { type: "anchor", parent, transform: { position: basePosition, rotation: [0, 0, baseRotation[2]], scale: ONE_VECTOR } };
    nodes[`${bone.id}_y`] = { type: "anchor", parent: `${bone.id}_z`, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    nodes[`${bone.id}_x`] = { type: "anchor", parent: `${bone.id}_y`, transform: { position: ZERO_VECTOR, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR } };
    for (const entry of bone.nodes) {
      const imported = importedNodes[entry.id];
      if (!imported) continue;
      nodes[entry.id] = importedNodeToRuntimeNode(imported, matrixToLocalTransform(matrix4ToRowMajor(entry.localMatrix, `${formatLabel} runtime node ${entry.id}`), `${formatLabel} runtime node ${entry.id}`), `${bone.id}_x`);
      editorNodeByRuntimeNode[entry.id] = entry.id;
    }
    const animator = animators.get(bone.uuid);
    if (!animator) continue;
    const positionSampling = blockbenchChannelSampling(source, bone.uuid, "position");
    const rotationSampling = blockbenchChannelSampling(source, bone.uuid, "rotation");
    const scaleSampling = blockbenchChannelSampling(source, bone.uuid, "scale");
    const position = blockbenchChannelFrames(animator, "position", ZERO_VECTOR, durationTicks, startDelayTicks, positionSampling?.sourceTimes, positionSampling?.stepTicks, (values) => transforms.position(values, negateMolang)
      .map((value, axis) => affineMolang(value, blendWeight / 16, basePosition[axis])) as MolangVector, options);
    const rotation = blockbenchChannelFrames(animator, "rotation", ZERO_VECTOR, durationTicks, startDelayTicks, rotationSampling?.sourceTimes, rotationSampling?.stepTicks, (values) => transforms.rotation(values, negateMolang)
      .map((value) => affineMolang(value, blendWeight, 0)) as MolangVector, options);
    const scale = blockbenchChannelFrames(animator, "scale", ONE_VECTOR, durationTicks, startDelayTicks, scaleSampling?.sourceTimes, scaleSampling?.stepTicks, (values) => values
      .map((value) => affineMolang(value, blendWeight, 1 - blendWeight)) as MolangVector, options);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateMolangAxis(rotation, 2, (value) => affineMolang(value, 1, baseRotation[2])) };
      tracks[`${bone.id}_y`] = { rotation: isolateMolangAxis(rotation, 1, (value) => affineMolang(value, 1, baseRotation[1])) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateMolangAxis(rotation, 0, (value) => affineMolang(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
    }
    return { nodes, tracks, bindings: { editorNodeByRuntimeNode, editorSpaceGroupByRuntimeRoot: { [sceneId]: sceneId } } };
  };
}

function blockbenchChannelFrames(
  animator: BbAnimator,
  channel: "position" | "rotation" | "scale",
  fallback: readonly [number, number, number],
  durationTicks: number,
  startDelayTicks: number,
  sourceTimes: ReadonlyMap<number, number> | undefined,
  stepTicks: ReadonlySet<number> | undefined,
  transform: (value: MolangVector) => MolangVector,
  options: BlockbenchNativeRuntimeOptions,
): RuntimeVectorKeyframe[] | undefined {
  const { channels, transforms } = options;
  const source = (animator.keyframes ?? []).filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
  if (source.length === 0) return undefined;
  const usesRuntimeState = source.some((frame) => frame.data_points.some((point) => usesRuntimeMolangState(point.x) || usesRuntimeMolangState(point.y) || usesRuntimeMolangState(point.z)));
  if (!usesRuntimeState && channels.canBake(source, channel, [...fallback], `runtime.${channel}`)) {
    const baked = Array.from({ length: durationTicks + 1 }, (_, tick): RuntimeVectorKeyframe => {
      const sourceTime = startDelayTicks > 0 ? (tick - startDelayTicks) / TICKS_PER_SECOND : sourceTimes?.get(tick) ?? tick / TICKS_PER_SECOND;
      const value = transform(channels.evaluate(source, channel, sourceTime, [...fallback], `runtime.${channel}`) as MolangVector);
      return {
        tick,
        value,
        ...(tick < durationTicks ? { interpolation: stepTicks?.has(tick + 1) ? "step" : "linear" } : {}),
      };
    });
    while (baked.length > 1 && sameVectorValue(baked.at(-2)!, baked.at(-1)!)) baked.pop();
    return withoutLastInterpolation(baked);
  }
  const result = [...new Map(source.map((frame, frameIndex): [number, RuntimeVectorKeyframe] => {
    if (frame.data_points.length < 1 || frame.data_points.length > 2) throw options.invalidPointCount();
    const vectors = frame.data_points.map((point) => transform(blockbenchPointVector(point, options)));
    const interpolation: RuntimeVectorKeyframe["interpolation"] = frame.interpolation === "step" ? "step" : "linear";
    const easing = blockbenchEasingToEmote(source[frameIndex + 1]?.easing);
    const converted = vectors.length === 1
      ? { tick: startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND), value: vectors[0], interpolation, ...(easing && interpolation !== "step" ? { easing } : {}) }
      : { tick: startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND), pre: vectors[0], post: vectors[1], interpolation, ...(easing && interpolation !== "step" ? { easing } : {}) };
    return [converted.tick, converted];
  })).values()];
  if (result[0].tick !== 0) result.unshift({ tick: 0, value: transform([...fallback] as MolangVector), interpolation: "step" });
  return withoutLastInterpolation(result);
}

function blockbenchPointVector(point: BbKeyframe["data_points"][number], options: BlockbenchNativeRuntimeOptions): MolangVector {
  const { transforms } = options;
  if (point.x === undefined || point.y === undefined || point.z === undefined) throw options.missingAxis();
  return [point.x, point.y, point.z].map((value) => {
    const scalar = molangScalar(value);
    return typeof scalar === "string" ? transforms.runtimeMolang(scalar) : scalar;
  }) as MolangVector;
}

function withoutLastInterpolation(frames: RuntimeVectorKeyframe[]): RuntimeVectorKeyframe[] {
  return frames.map((frame, index) => index + 1 < frames.length ? frame : (({ interpolation: _, easing: __, ...last }) => last)(frame));
}

function sameVectorValue(first: RuntimeVectorKeyframe, second: RuntimeVectorKeyframe): boolean {
  return first.value !== undefined && second.value !== undefined && first.value.every((value, axis) => value === second.value![axis]);
}
