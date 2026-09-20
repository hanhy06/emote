import type { RuntimeNode, RuntimeNodeTracks, RuntimeScalar, RuntimeVectorKeyframe } from "../../domain/minecraftData";
import { TICKS_PER_SECOND } from "../../format/time";
import type { ImportedAnimation, ImportedNode } from "../../domain/conversionSeed";
import { affineMolang, isolateMolangAxis, molangScalar, type MolangVector } from "../common/molangVector";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../common/runtimeOutput";
import { blockbenchEasingToEmote, blockbenchIntervalIsStep } from "../common/animationEasing";
import { usesRuntimeMolangState } from "../../format/molang/runtimeAnalysis";
import type { AjProjectAnimation, AjProjectDisplayElement, AjProjectKeyframe } from "./animatedJavaProjectSchema";
import { ANIMATED_JAVA_CHANNELS } from "./animatedJavaAnimationPolicy";

export interface AjRuntimeHierarchy {
  sceneId?: string;
  runtimeParentByGroupUuid: Readonly<Record<string, string>>;
  parentGroupByElementUuid: ReadonlyMap<string, string | undefined>;
  groupOrigins: ReadonlyMap<string, readonly number[]>;
}

export function createAnimatedJavaRuntime(
  animation: AjProjectAnimation,
  elements: AjProjectDisplayElement[],
  importedNodes: Record<string, ImportedNode>,
  hierarchy: AjRuntimeHierarchy,
  blendWeight: number,
  startDelayTicks = 0,
  durationTicks = startDelayTicks + Math.max(1, Math.round(animation.length * TICKS_PER_SECOND)),
  cubeRuntime?: ImportedAnimation["runtime"],
): Omit<Extract<ImportedAnimation["runtime"], { kind: "native" }>, "kind"> {
  if (cubeRuntime && cubeRuntime.kind !== "native") throw new Error("Animated Java cube runtime must use native animation output.");
  const nodes: Record<string, RuntimeNode> = { ...(cubeRuntime?.nodes ?? {}) };
  const tracks: Record<string, RuntimeNodeTracks> = { ...(cubeRuntime?.tracks ?? {}) };
  const editorNodeByRuntimeNode: Record<string, string> = { ...(cubeRuntime?.bindings.editorNodeByRuntimeNode ?? {}) };
  const editorSpaceGroupByRuntimeRoot: Record<string, string> = { ...(cubeRuntime?.bindings.editorSpaceGroupByRuntimeRoot ?? {}) };
  const addNode = (id: string, node: RuntimeNode) => {
    if (nodes[id]) throw new Error(`Animated Java runtime produces more than one node named ${id}.`);
    nodes[id] = node;
  };
  for (const element of elements) {
    const sourceNode = importedNodes[element.uuid];
    if (!sourceNode) continue;
    const ids = ajAnchorIds(element.uuid);
    const parentGroupUuid = hierarchy.parentGroupByElementUuid.get(element.uuid);
    const parentId = parentGroupUuid ? hierarchy.runtimeParentByGroupUuid[parentGroupUuid] : hierarchy.sceneId;
    if (parentGroupUuid && !parentId) throw new Error(`Animated Java display ${element.name} references an unavailable runtime group ${parentGroupUuid}.`);
    const parentOrigin = parentGroupUuid ? hierarchy.groupOrigins.get(parentGroupUuid) : undefined;
    const spaceGroup = sourceNode.binding.spaceGroupId ?? ajRuntimeRootId(element.uuid);
    const basePosition = element.position.map((value, axis) => (value - (parentOrigin?.[axis] ?? 0)) / 16) as [number, number, number];
    const baseRotation = element.rotation;
    addNode(ids.x, {
      type: "anchor",
      ...(parentId ? { parent: parentId } : { space: sourceNode.space ?? "initiator" }),
      transform: { position: basePosition, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR },
    });
    addNode(ids.y, { type: "anchor", parent: ids.x, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } });
    const baseScale = [...element.scale] as [number, number, number];
    addNode(ids.z, { type: "anchor", parent: ids.y, transform: { position: ZERO_VECTOR, rotation: [0, 0, baseRotation[2]], scale: baseScale } });
    const nodeTransform = element.type === "animated_java:vanilla_text_display" || element.type === "animated_java:text_display"
      ? { ...IDENTITY_TRANSFORM, rotation: [0, 180, 0] as const }
      : IDENTITY_TRANSFORM;
    addNode(element.uuid, importedNodeToRuntimeNode(sourceNode, nodeTransform, ids.z));
    editorNodeByRuntimeNode[element.uuid] = element.uuid;
    if (!parentId) editorSpaceGroupByRuntimeRoot[ids.x] = spaceGroup;
    const keyframes = animation.animators[element.uuid]?.keyframes ?? [];
    const position = ajProjectFrames(keyframes, "position", ZERO_VECTOR, basePosition, startDelayTicks, durationTicks, (value, axis) => affineMolang(value, (axis === 0 ? -1 : 1) * blendWeight / 16, basePosition[axis]));
    const rotation = ajProjectFrames(keyframes, "rotation", ZERO_VECTOR, ZERO_VECTOR, startDelayTicks, durationTicks, (value, axis) => affineMolang(value, (axis === 2 ? 1 : -1) * blendWeight, 0));
    const scale = ajProjectFrames(keyframes, "scale", ONE_VECTOR, baseScale, startDelayTicks, durationTicks, (value, axis) => {
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
  return {
    ...(cubeRuntime?.molang ? { molang: cubeRuntime.molang } : {}),
    nodes,
    tracks,
    bindings: { editorNodeByRuntimeNode, editorSpaceGroupByRuntimeRoot },
  };
}

function ajProjectFrames(
  keyframes: AjProjectKeyframe[],
  channel: "position" | "rotation" | "scale",
  sourceFallback: readonly number[],
  runtimeFallback: readonly number[],
  startDelayTicks: number,
  durationTicks: number,
  transform: (value: RuntimeScalar, axis: number) => RuntimeScalar,
): RuntimeVectorKeyframe[] | undefined {
  const source = keyframes.filter((frame) => frame.channel === channel).sort((a, b) => a.time - b.time);
  if (source.length === 0) return undefined;
  const usesRuntimeState = source.some((frame) => frame.data_points.some((point) => usesRuntimeMolangState(point.x) || usesRuntimeMolangState(point.y) || usesRuntimeMolangState(point.z)));
  if (!usesRuntimeState && ANIMATED_JAVA_CHANNELS.canBake(source, channel, [...sourceFallback], `runtime.${channel}`)) {
    const baked = Array.from({ length: durationTicks + 1 }, (_, tick): RuntimeVectorKeyframe => {
      const animationTick = tick - startDelayTicks;
      const roundedAnchors = source.filter((frame) => Math.round(frame.time * TICKS_PER_SECOND) === animationTick);
      const sourceTime = roundedAnchors.at(-1)?.time ?? animationTick / TICKS_PER_SECOND;
      return {
        tick,
        value: ANIMATED_JAVA_CHANNELS.evaluate(source, channel, sourceTime, [...sourceFallback], `runtime.${channel}`)
          .map((value, axis) => transform(value, axis)) as MolangVector,
        ...(tick < durationTicks ? { interpolation: blockbenchIntervalIsStep(source, sourceTime, sourceTime + 1 / TICKS_PER_SECOND) ? "step" : "linear" } : {}),
      };
    });
    while (baked.length > 1 && sameVectorValue(baked.at(-2)!, baked.at(-1)!)) baked.pop();
    return withoutLastInterpolation(baked);
  }
  const frames = [...new Map(source.map((frame, frameIndex): [number, RuntimeVectorKeyframe] => {
    const points = frame.data_points;
    if (points.length < 1 || points.length > 2) throw new Error("Animated Java transform keyframes must contain one value or a pre/post pair.");
    const vectors = points.map((point) => {
      if (point.x === undefined || point.y === undefined || point.z === undefined) throw new Error("Animated Java transform keyframe is missing an axis value.");
      return [point.x, point.y, point.z].map((value, axis) => transform(molangScalar(value), axis)) as MolangVector;
    });
    const interpolation: RuntimeVectorKeyframe["interpolation"] = frame.interpolation === "step" ? "step" : "linear";
    const easing = blockbenchEasingToEmote(source[frameIndex + 1]?.easing);
    const result = vectors.length === 1
      ? { tick: startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND), value: vectors[0], interpolation, ...(easing && interpolation !== "step" ? { easing } : {}) }
      : { tick: startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND), pre: vectors[0], post: vectors[1], interpolation, ...(easing && interpolation !== "step" ? { easing } : {}) };
    return [result.tick, result];
  })).values()];
  if (frames[0].tick !== 0) frames.unshift({ tick: 0, value: [...runtimeFallback] as MolangVector, interpolation: "step" });
  return withoutLastInterpolation(frames);
}

function ajAnchorIds(id: string) {
  return { y: `aj_${id}_y`, x: `aj_${id}_x`, z: `aj_${id}_z` };
}

export function ajRuntimeRootId(id: string): string {
  return ajAnchorIds(id).x;
}

function withoutLastInterpolation(frames: RuntimeVectorKeyframe[]): RuntimeVectorKeyframe[] {
  return frames.map((frame, index) => {
    if (index + 1 < frames.length) return frame;
    const { interpolation: _, easing: __, ...last } = frame;
    return last;
  });
}

function multiply(first: RuntimeScalar, second: RuntimeScalar): RuntimeScalar {
  if (typeof first === "number" && typeof second === "number") return first * second;
  return `((${first}) * (${second}))`;
}

function sameVectorValue(first: RuntimeVectorKeyframe, second: RuntimeVectorKeyframe): boolean {
  return first.value !== undefined && second.value !== undefined && first.value.every((value, axis) => value === second.value![axis]);
}
