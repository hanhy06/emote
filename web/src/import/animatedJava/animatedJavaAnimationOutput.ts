import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import type { EmoteNode, EmoteNodeTracks, EmoteVectorKeyframe, Matrix16, MolangScalar } from "../../format/emoteAnimation";
import { matrixToLocalTransform } from "../../format/localTransform";
import { formatMinecraftTime } from "../../format/time";
import type { ImportedAnimation, ImportedNode } from "../../domain/conversionSeed";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../runtimeOutput";
import type { AjAnimation, AjKeyframe, AjNodeChannels } from "./animatedJavaSchema";
import type { AjProjectAnimation, AjProjectDisplayElement, AjProjectKeyframe } from "./animatedJavaProjectSchema";

type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

export interface AjPoseTarget {
  id: string;
  localMatrix?: Matrix16;
}

export function createAjBlueprintRuntime(
  animation: AjAnimation,
  durationTicks: number,
  targetsBySource: ReadonlyMap<string, readonly AjPoseTarget[]>,
  importedNodes: Record<string, ImportedNode>,
): NonNullable<ImportedAnimation["runtime"]> {
  const nodes: Record<string, EmoteNode> = {};
  const tracks: Record<string, EmoteNodeTracks> = {};
  const animatedNodeIds = new Set<string>();
  const startDelayTicks = numericTicks(animation.start_delay);
  for (const [sourceId, channels] of Object.entries(animation.node_keyframes ?? {})) {
    const targets = targetsBySource.get(sourceId) ?? [];
    const sourceNode = importedNodes[targets[0]?.id];
    if (!sourceNode) continue;
    targets.forEach(({ id }) => animatedNodeIds.add(id));
    const base = blueprintTransform(sourceNode);
    const ids = ajAnchorIds(sourceId);
    nodes[ids.y] = { type: "anchor", space: sourceNode.space ?? "initiator", transform: { position: base.position, rotation: [0, 180 - base.ajRotation[1], 0], scale: ONE_VECTOR } };
    nodes[ids.x] = { type: "anchor", parent: ids.y, transform: { position: ZERO_VECTOR, rotation: [-base.ajRotation[0], 0, 0], scale: ONE_VECTOR } };
    nodes[ids.z] = { type: "anchor", parent: ids.x, transform: { position: ZERO_VECTOR, rotation: [0, 0, base.ajRotation[2]], scale: base.scale } };
    for (const target of targets) {
      const transform = target.localMatrix
        ? matrixToLocalTransform(target.localMatrix, `Animated Java runtime target ${target.id}`)
        : IDENTITY_TRANSFORM;
      nodes[target.id] = importedNodeToRuntimeNode(importedNodes[target.id], transform, ids.z);
    }
    const position = ajChannelFrames(channels.position, base.position, startDelayTicks, animation.blend_weight ?? "1", (value) => value);
    const rotation = ajChannelFrames(channels.rotation, base.ajRotation, startDelayTicks, animation.blend_weight ?? "1", (value) => value);
    const scale = ajChannelFrames(channels.scale, base.scale, startDelayTicks, animation.blend_weight ?? "1", (value) => value);
    if (position) tracks[ids.y] = { position };
    if (rotation) {
      tracks[ids.y] = { ...tracks[ids.y], rotation: isolateAjAxis(rotation, 1, (value) => affine(value, -1, 180)) };
      tracks[ids.x] = { rotation: isolateAjAxis(rotation, 0, (value) => affine(value, -1, 0)) };
      tracks[ids.z] = { ...tracks[ids.z], rotation: isolateAjAxis(rotation, 2, (value) => value) };
    }
    if (scale) tracks[ids.z] = { ...tracks[ids.z], scale };
  }
  for (const [id, node] of Object.entries(importedNodes)) {
    if (!animatedNodeIds.has(id)) nodes[id] = importedNodeToRuntimeNode(node, matrixToLocalTransform(node.defaultMatrix, `Animated Java runtime node ${id}`));
  }
  return { nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

export function createAjProjectRuntime(
  animation: AjProjectAnimation,
  durationTicks: number,
  elements: AjProjectDisplayElement[],
  importedNodes: Record<string, ImportedNode>,
): NonNullable<ImportedAnimation["runtime"]> {
  const nodes: Record<string, EmoteNode> = {};
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const element of elements) {
    const sourceNode = importedNodes[element.uuid];
    if (!sourceNode) continue;
    const ids = ajAnchorIds(element.uuid);
    const basePosition = element.position.map((value) => value / 16) as [number, number, number];
    nodes[ids.z] = { type: "anchor", space: sourceNode.space ?? "initiator", transform: { position: basePosition, rotation: [0, 0, element.rotation[2]], scale: ONE_VECTOR } };
    nodes[ids.y] = { type: "anchor", parent: ids.z, transform: { position: ZERO_VECTOR, rotation: [0, element.rotation[1], 0], scale: ONE_VECTOR } };
    nodes[ids.x] = { type: "anchor", parent: ids.y, transform: { position: ZERO_VECTOR, rotation: [element.rotation[0], 0, 0], scale: element.scale as [number, number, number] } };
    nodes[element.uuid] = importedNodeToRuntimeNode(sourceNode, IDENTITY_TRANSFORM, ids.x);
    const keyframes = animation.animators[element.uuid]?.keyframes ?? [];
    const position = ajProjectFrames(keyframes, "position", basePosition, (value, axis) => affine(value, 1 / 16, basePosition[axis]));
    const rotation = ajProjectFrames(keyframes, "rotation", ZERO_VECTOR, (value) => value);
    const scale = ajProjectFrames(keyframes, "scale", element.scale, (value, axis) => multiply(value, element.scale[axis]));
    if (position) tracks[ids.z] = { position };
    if (rotation) {
      tracks[ids.z] = { ...tracks[ids.z], rotation: isolateAjAxis(rotation, 2, (value) => affine(value, 1, element.rotation[2])) };
      tracks[ids.y] = { rotation: isolateAjAxis(rotation, 1, (value) => affine(value, 1, element.rotation[1])) };
      tracks[ids.x] = { ...tracks[ids.x], rotation: isolateAjAxis(rotation, 0, (value) => affine(value, 1, element.rotation[0])) };
    }
    if (scale) tracks[ids.x] = { ...tracks[ids.x], scale };
  }
  return { nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

function ajChannelFrames(
  channel: Record<string, AjKeyframe> | undefined,
  fallback: readonly [number, number, number],
  startDelayTicks: number,
  blendWeight: string,
  transform: (value: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  const source = Object.entries(channel ?? {}).map(([time, frame]) => ({ time: Number(time), frame })).sort((a, b) => a.time - b.time);
  if (source.length === 0) return undefined;
  const frames = source.map(({ time, frame }): EmoteVectorKeyframe => {
    const value = transform(blendVector(fallback, vector(frame.value), blendWeight));
    const post = frame.post ? transform(blendVector(fallback, vector(frame.post), blendWeight)) : undefined;
    const interpolation = ajInterpolation(frame);
    return post
      ? { time: formatMinecraftTime(Math.round(time * 20) + startDelayTicks), pre: value, post, interpolation }
      : { time: formatMinecraftTime(Math.round(time * 20) + startDelayTicks), value, interpolation };
  });
  if (frames[0].time !== "0t") frames.unshift({ time: "0t", value: [...fallback] as MolangVector, interpolation: "step" });
  return withoutLastInterpolation(frames);
}

function ajProjectFrames(
  keyframes: AjProjectKeyframe[],
  channel: "position" | "rotation" | "scale",
  fallback: readonly number[],
  transform: (value: MolangScalar, axis: number) => MolangScalar,
): EmoteVectorKeyframe[] | undefined {
  const source = keyframes.filter((frame) => frame.channel === channel).sort((a, b) => a.time - b.time);
  if (source.length === 0) return undefined;
  const frames = source.map((frame): EmoteVectorKeyframe => {
    const points = frame.data_points;
    if (points.length < 1 || points.length > 2) throw new Error("Animated Java transform keyframes must contain one value or a pre/post pair.");
    const vectors = points.map((point) => [point.x, point.y, point.z].map((value, axis) => transform(scalar(value), axis)) as MolangVector);
    const interpolation = frame.interpolation === "step" || frame.easing === "step" ? "step" : "linear";
    return vectors.length === 1
      ? { time: formatMinecraftTime(Math.round(frame.time * 20)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(Math.round(frame.time * 20)), pre: vectors[0], post: vectors[1], interpolation };
  });
  if (frames[0].time !== "0t") frames.unshift({ time: "0t", value: [...fallback] as MolangVector, interpolation: "step" });
  return withoutLastInterpolation(frames);
}

function blueprintTransform(node: ImportedNode): { position: [number, number, number]; ajRotation: [number, number, number]; scale: [number, number, number] } {
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  new Matrix4().set(...node.defaultMatrix).decompose(position, rotation, scale);
  const euler = new Euler().setFromQuaternion(rotation, "YXZ");
  return {
    position: position.toArray(),
    ajRotation: [-MathUtils.radToDeg(euler.x), 180 - MathUtils.radToDeg(euler.y), MathUtils.radToDeg(euler.z)],
    scale: scale.toArray(),
  };
}

function ajAnchorIds(id: string) {
  return { y: `aj_${id}_y`, x: `aj_${id}_x`, z: `aj_${id}_z` };
}

function vector(values: string[]): MolangVector {
  if (values.length !== 3) throw new Error("Animated Java transform vector must contain three values.");
  return values.map(scalar) as MolangVector;
}

function scalar(value: string | number): MolangScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

function blendVector(base: readonly number[], target: MolangVector, weight: string): MolangVector {
  const parsed = Number(weight.trim());
  const blend: MolangScalar = Number.isFinite(parsed) ? parsed : weight.trim();
  return target.map((value, axis) => {
    if (typeof blend === "number" && blend === 1) return value;
    const difference = affine(value, 1, -base[axis]);
    return affine(multiply(difference, blend), 1, base[axis]);
  }) as MolangVector;
}

function isolateAjAxis(frames: EmoteVectorKeyframe[], axis: number, transform: (value: MolangScalar) => MolangScalar): EmoteVectorKeyframe[] {
  const isolate = (values: readonly MolangScalar[]): MolangVector => values.map((value, index) => index === axis ? transform(value) : 0) as MolangVector;
  return frames.map((frame) => ({ ...frame, ...(frame.value ? { value: isolate(frame.value) } : {}), ...(frame.pre ? { pre: isolate(frame.pre) } : {}), ...(frame.post ? { post: isolate(frame.post) } : {}) }));
}

function ajInterpolation(frame: AjKeyframe): "linear" | "step" {
  if (frame.interpolation.type === "step") return "step";
  if (frame.interpolation.type === "linear" && frame.interpolation.easing === "step") return "step";
  return "linear";
}

function withoutLastInterpolation(frames: EmoteVectorKeyframe[]): EmoteVectorKeyframe[] {
  return frames.map((frame, index) => {
    if (index + 1 < frames.length) return frame;
    const { interpolation: _, easing: __, ...last } = frame;
    return last;
  });
}

function affine(value: MolangScalar, factor: number, offset: number): MolangScalar {
  if (typeof value === "number") return value * factor + offset;
  const scaled = factor === 1 ? `(${value})` : `((${value}) * ${factor})`;
  return offset === 0 ? scaled : `(${scaled} + ${offset})`;
}

function multiply(first: MolangScalar, second: MolangScalar): MolangScalar {
  if (typeof first === "number" && typeof second === "number") return first * second;
  return `((${first}) * (${second}))`;
}

function numericTicks(value: string | undefined): number {
  if (!value) return 0;
  const parsed = Number(value.trim());
  return Number.isFinite(parsed) ? Math.max(0, Math.round(parsed * 20)) : 0;
}
