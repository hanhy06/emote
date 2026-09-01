import type { EmoteNode, EmoteNodeTracks, EmoteVectorKeyframe, MolangScalar } from "../../format/emoteAnimation";
import { formatMinecraftTime } from "../../format/time";
import type { ImportedAnimation, ImportedNode } from "../../domain/conversionSeed";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../runtimeOutput";
import type { AjProjectAnimation, AjProjectDisplayElement, AjProjectKeyframe } from "./animatedJavaProjectSchema";

type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

export function createAjProjectRuntime(
  animation: AjProjectAnimation,
  durationTicks: number,
  elements: AjProjectDisplayElement[],
  importedNodes: Record<string, ImportedNode>,
  sceneScale: number,
): NonNullable<ImportedAnimation["runtime"]> {
  const nodes: Record<string, EmoteNode> = {};
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const element of elements) {
    const sourceNode = importedNodes[element.uuid];
    if (!sourceNode) continue;
    const ids = ajAnchorIds(element.uuid);
    const basePosition = [-element.position[0] * sceneScale / 16, element.position[1] * sceneScale / 16, element.position[2] * sceneScale / 16] as [number, number, number];
    const baseRotation = [element.rotation[0], -element.rotation[1], -element.rotation[2]] as [number, number, number];
    nodes[ids.z] = { type: "anchor", space: sourceNode.space ?? "initiator", transform: { position: basePosition, rotation: [0, 0, baseRotation[2]], scale: ONE_VECTOR } };
    nodes[ids.y] = { type: "anchor", parent: ids.z, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    const baseScale = element.scale.map((value) => value * sceneScale) as [number, number, number];
    nodes[ids.x] = { type: "anchor", parent: ids.y, transform: { position: ZERO_VECTOR, rotation: [baseRotation[0], 0, 0], scale: baseScale } };
    nodes[element.uuid] = importedNodeToRuntimeNode(sourceNode, IDENTITY_TRANSFORM, ids.x);
    const keyframes = animation.animators[element.uuid]?.keyframes ?? [];
    const position = ajProjectFrames(keyframes, "position", basePosition, (value, axis) => affine(value, sceneScale / 16, basePosition[axis]));
    const rotation = ajProjectFrames(keyframes, "rotation", ZERO_VECTOR, (value, axis) => axis === 1 ? value : affine(value, -1, 0));
    const scale = ajProjectFrames(keyframes, "scale", baseScale, (value, axis) => multiply(value, baseScale[axis]));
    if (position) tracks[ids.z] = { position };
    if (rotation) {
      tracks[ids.z] = { ...tracks[ids.z], rotation: isolateAjAxis(rotation, 2, (value) => affine(value, 1, baseRotation[2])) };
      tracks[ids.y] = { rotation: isolateAjAxis(rotation, 1, (value) => affine(value, 1, baseRotation[1])) };
      tracks[ids.x] = { ...tracks[ids.x], rotation: isolateAjAxis(rotation, 0, (value) => affine(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[ids.x] = { ...tracks[ids.x], scale };
  }
  return { nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
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
    const vectors = points.map((point) => {
      if (point.x === undefined || point.y === undefined || point.z === undefined) throw new Error("Animated Java transform keyframe is missing an axis value.");
      return [point.x, point.y, point.z].map((value, axis) => transform(scalar(value), axis)) as MolangVector;
    });
    const interpolation = frame.interpolation === "step" || frame.easing === "step" ? "step" : "linear";
    return vectors.length === 1
      ? { time: formatMinecraftTime(Math.round(frame.time * 20)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(Math.round(frame.time * 20)), pre: vectors[0], post: vectors[1], interpolation };
  });
  if (frames[0].time !== "0t") frames.unshift({ time: "0t", value: [...fallback] as MolangVector, interpolation: "step" });
  return withoutLastInterpolation(frames);
}

function ajAnchorIds(id: string) {
  return { y: `aj_${id}_y`, x: `aj_${id}_x`, z: `aj_${id}_z` };
}

function scalar(value: string | number): MolangScalar {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : value.trim();
}

function isolateAjAxis(frames: EmoteVectorKeyframe[], axis: number, transform: (value: MolangScalar) => MolangScalar): EmoteVectorKeyframe[] {
  const isolate = (values: readonly MolangScalar[]): MolangVector => values.map((value, index) => index === axis ? transform(value) : 0) as MolangVector;
  return frames.map((frame) => ({ ...frame, ...(frame.value ? { value: isolate(frame.value) } : {}), ...(frame.pre ? { pre: isolate(frame.pre) } : {}), ...(frame.post ? { post: isolate(frame.post) } : {}) }));
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
