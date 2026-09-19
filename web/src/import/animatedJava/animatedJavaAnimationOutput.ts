import type { RuntimeNode, RuntimeNodeTracks } from "../../domain/minecraftData";
import type { EmoteVectorKeyframe, MolangScalar } from "../../format/emoteAnimation";
import { formatMinecraftTime } from "../../format/time";
import type { ImportedAnimation, ImportedNode } from "../../domain/conversionSeed";
import { affineMolang, isolateMolangAxis, molangScalar, type MolangVector } from "../common/molangVector";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../common/runtimeOutput";
import type { AjProjectAnimation, AjProjectDisplayElement, AjProjectKeyframe } from "./animatedJavaProjectSchema";

export interface AjRuntimeHierarchy {
  sceneId?: string;
  runtimeParentByGroupUuid: Readonly<Record<string, string>>;
  parentGroupByElementUuid: ReadonlyMap<string, string | undefined>;
  groupOrigins: ReadonlyMap<string, readonly number[]>;
}

export function createAjProjectRuntime(
  animation: AjProjectAnimation,
  elements: AjProjectDisplayElement[],
  importedNodes: Record<string, ImportedNode>,
  hierarchy: AjRuntimeHierarchy,
  blendWeight: number,
  startDelayTicks = 0,
): Omit<Extract<ImportedAnimation["runtime"], { kind: "native" }>, "kind"> {
  const nodes: Record<string, RuntimeNode> = {};
  const tracks: Record<string, RuntimeNodeTracks> = {};
  const editorNodeByRuntimeNode: Record<string, string> = {};
  const spaceGroupByRuntimeRoot: Record<string, string> = {};
  for (const element of elements) {
    const sourceNode = importedNodes[element.uuid];
    if (!sourceNode) continue;
    const ids = ajAnchorIds(element.uuid);
    const parentGroupUuid = hierarchy.parentGroupByElementUuid.get(element.uuid);
    const parentId = parentGroupUuid ? hierarchy.runtimeParentByGroupUuid[parentGroupUuid] : hierarchy.sceneId;
    if (parentGroupUuid && !parentId) throw new Error(`Animated Java display ${element.name} references an unavailable runtime group ${parentGroupUuid}.`);
    const parentOrigin = parentGroupUuid ? hierarchy.groupOrigins.get(parentGroupUuid) : undefined;
    const spaceGroup = sourceNode.spaceAssignmentGroup ?? ajRuntimeRootId(element.uuid);
    const basePosition = element.position.map((value, axis) => (value - (parentOrigin?.[axis] ?? 0)) / 16) as [number, number, number];
    const baseRotation = element.rotation;
    nodes[ids.x] = {
      type: "anchor",
      ...(parentId ? { parent: parentId } : { space: sourceNode.space ?? "initiator" }),
      transform: { position: basePosition, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR },
    };
    nodes[ids.y] = { type: "anchor", parent: ids.x, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    const baseScale = [...element.scale] as [number, number, number];
    nodes[ids.z] = { type: "anchor", parent: ids.y, transform: { position: ZERO_VECTOR, rotation: [0, 0, baseRotation[2]], scale: baseScale } };
    const nodeTransform = element.type === "animated_java:vanilla_text_display" || element.type === "animated_java:text_display"
      ? { ...IDENTITY_TRANSFORM, rotation: [0, 180, 0] as const }
      : IDENTITY_TRANSFORM;
    nodes[element.uuid] = importedNodeToRuntimeNode(sourceNode, nodeTransform, ids.z);
    editorNodeByRuntimeNode[element.uuid] = element.uuid;
    if (!parentId) spaceGroupByRuntimeRoot[ids.x] = spaceGroup;
    const keyframes = animation.animators[element.uuid]?.keyframes ?? [];
    const position = ajProjectFrames(keyframes, "position", basePosition, startDelayTicks, (value, axis) => affineMolang(value, (axis === 0 ? -1 : 1) * blendWeight / 16, basePosition[axis]));
    const rotation = ajProjectFrames(keyframes, "rotation", ZERO_VECTOR, startDelayTicks, (value, axis) => affineMolang(value, (axis === 2 ? 1 : -1) * blendWeight, 0));
    const scale = ajProjectFrames(keyframes, "scale", baseScale, startDelayTicks, (value, axis) => {
      const blended = affineMolang(value, blendWeight, 1 - blendWeight);
      return element.type === "animated_java:vanilla_item_display" ? blended : multiply(blended, baseScale[axis]);
    });
    if (position) tracks[ids.x] = { position };
    if (rotation) {
      tracks[ids.z] = { ...tracks[ids.z], rotation: isolateMolangAxis(rotation, 2, (value) => affineMolang(value, 1, baseRotation[2])) };
      tracks[ids.y] = { rotation: isolateMolangAxis(rotation, 1, (value) => affineMolang(value, 1, baseRotation[1])) };
      tracks[ids.x] = { ...tracks[ids.x], rotation: isolateMolangAxis(rotation, 0, (value) => affineMolang(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[ids.z] = { ...tracks[ids.z], scale };
  }
  return { nodes, tracks, bindings: { editorNodeByRuntimeNode, spaceGroupByRuntimeRoot } };
}

function ajProjectFrames(
  keyframes: AjProjectKeyframe[],
  channel: "position" | "rotation" | "scale",
  fallback: readonly number[],
  startDelayTicks: number,
  transform: (value: MolangScalar, axis: number) => MolangScalar,
): EmoteVectorKeyframe[] | undefined {
  const source = keyframes.filter((frame) => frame.channel === channel).sort((a, b) => a.time - b.time);
  if (source.length === 0) return undefined;
  const frames = source.map((frame): EmoteVectorKeyframe => {
    const points = frame.data_points;
    if (points.length < 1 || points.length > 2) throw new Error("Animated Java transform keyframes must contain one value or a pre/post pair.");
    const vectors = points.map((point) => {
      if (point.x === undefined || point.y === undefined || point.z === undefined) throw new Error("Animated Java transform keyframe is missing an axis value.");
      return [point.x, point.y, point.z].map((value, axis) => transform(molangScalar(value), axis)) as MolangVector;
    });
    const interpolation = frame.interpolation === "step" || frame.easing === "step" ? "step" : "linear";
    return vectors.length === 1
      ? { time: formatMinecraftTime(startDelayTicks + Math.round(frame.time * 20)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(startDelayTicks + Math.round(frame.time * 20)), pre: vectors[0], post: vectors[1], interpolation };
  });
  if (frames[0].time !== "0t") frames.unshift({ time: "0t", value: [...fallback] as MolangVector, interpolation: "step" });
  return withoutLastInterpolation(frames);
}

function ajAnchorIds(id: string) {
  return { y: `aj_${id}_y`, x: `aj_${id}_x`, z: `aj_${id}_z` };
}

export function ajRuntimeRootId(id: string): string {
  return ajAnchorIds(id).x;
}

function withoutLastInterpolation(frames: EmoteVectorKeyframe[]): EmoteVectorKeyframe[] {
  return frames.map((frame, index) => {
    if (index + 1 < frames.length) return frame;
    const { interpolation: _, easing: __, ...last } = frame;
    return last;
  });
}

function multiply(first: MolangScalar, second: MolangScalar): MolangScalar {
  if (typeof first === "number" && typeof second === "number") return first * second;
  return `((${first}) * (${second}))`;
}
