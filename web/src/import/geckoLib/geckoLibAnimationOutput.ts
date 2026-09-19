import type { RuntimeNode, RuntimeNodeTracks } from "../../domain/minecraftData";
import type { EmoteVectorKeyframe } from "../../format/emoteAnimation";
import { matrixToLocalTransform } from "../../format/localTransform";
import { matrix4ToRowMajor } from "../../format/matrix";
import { formatMinecraftTime, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import {
  BLOCKBENCH_RUNTIME_SCENE_ID,
  PLAYER_RENDER_SCALE,
  type BlockbenchNativeRuntimeContext,
  type BlockbenchNativeRuntimeFactory,
} from "../common/blockbenchCubeImporter";
import type { BbAnimator, BbKeyframe } from "../common/blockbenchCubeSchema";
import { affineMolang, isolateMolangAxis, molangScalar, negateMolang, type MolangVector } from "../common/molangVector";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../common/runtimeOutput";
import { GECKOLIB_BBMODEL_TRANSFORMS } from "./geckoLibCubeTransform";

export const createGeckoLibRuntime: BlockbenchNativeRuntimeFactory = ({ bones, importedNodes, animators }: BlockbenchNativeRuntimeContext) => {
  const sceneId = BLOCKBENCH_RUNTIME_SCENE_ID;
  const nodes: Record<string, RuntimeNode> = {
    [sceneId]: { type: "anchor", space: "initiator", transform: { ...IDENTITY_TRANSFORM, scale: [PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE] } },
  };
  const tracks: Record<string, RuntimeNodeTracks> = {};
  const editorNodeByRuntimeNode: Record<string, string> = {};
  for (const bone of bones) {
    const parent = bone.parent ? `${bone.parent.id}_x` : sceneId;
    const parentOrigin = bone.parent?.group.origin ?? ZERO_VECTOR;
    const basePosition = GECKOLIB_BBMODEL_TRANSFORMS.position(
      bone.group.origin.map((value, axis) => value - parentOrigin[axis]),
      (value) => -value,
    ).map((value) => value / 16) as [number, number, number];
    const baseRotation = GECKOLIB_BBMODEL_TRANSFORMS.rotation(bone.group.rotation, (value) => -value);
    nodes[`${bone.id}_z`] = { type: "anchor", parent, transform: { position: basePosition, rotation: [0, 0, baseRotation[2]], scale: ONE_VECTOR } };
    nodes[`${bone.id}_y`] = { type: "anchor", parent: `${bone.id}_z`, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    nodes[`${bone.id}_x`] = { type: "anchor", parent: `${bone.id}_y`, transform: { position: ZERO_VECTOR, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR } };
    for (const entry of bone.nodes) {
      const imported = importedNodes[entry.id];
      if (!imported) continue;
      nodes[entry.id] = importedNodeToRuntimeNode(imported, matrixToLocalTransform(matrix4ToRowMajor(entry.localMatrix, `GeckoLib runtime node ${entry.id}`), `GeckoLib runtime node ${entry.id}`), `${bone.id}_x`);
      editorNodeByRuntimeNode[entry.id] = entry.id;
    }
    const animator = animators.get(bone.uuid);
    if (!animator) continue;
    const position = geckoLibChannelFrames(animator, "position", ZERO_VECTOR, (values) => GECKOLIB_BBMODEL_TRANSFORMS.position(values, negateMolang)
      .map((value, axis) => affineMolang(value, 1 / 16, basePosition[axis])) as MolangVector);
    const rotation = geckoLibChannelFrames(animator, "rotation", ZERO_VECTOR, (values) => GECKOLIB_BBMODEL_TRANSFORMS.rotation(values, negateMolang));
    const scale = geckoLibChannelFrames(animator, "scale", ONE_VECTOR, (values) => values);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateMolangAxis(rotation, 2, (value) => affineMolang(value, 1, baseRotation[2])) };
      tracks[`${bone.id}_y`] = { rotation: isolateMolangAxis(rotation, 1, (value) => affineMolang(value, 1, baseRotation[1])) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateMolangAxis(rotation, 0, (value) => affineMolang(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
  }
  return { nodes, tracks, bindings: { editorNodeByRuntimeNode, spaceGroupByRuntimeRoot: { [sceneId]: sceneId } } };
};

function geckoLibChannelFrames(
  animator: BbAnimator,
  channel: "position" | "rotation" | "scale",
  fallback: readonly [number, number, number],
  transform: (value: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  const source = (animator.keyframes ?? []).filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
  if (source.length === 0) return undefined;
  const result = [...new Map(source.map((frame): [string, EmoteVectorKeyframe] => {
    if (frame.data_points.length < 1 || frame.data_points.length > 2) throw new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair.");
    const vectors = frame.data_points.map((point) => transform(geckoLibPointVector(point)));
    const interpolation: EmoteVectorKeyframe["interpolation"] = frame.interpolation === "step" ? "step" : "linear";
    const converted = vectors.length === 1
      ? { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), pre: vectors[0], post: vectors[1], interpolation };
    return [converted.time, converted];
  })).values()];
  if (result[0].time !== "0t") result.unshift({ time: "0t", value: transform([...fallback] as MolangVector), interpolation: "step" });
  return result.map((frame, index) => index + 1 < result.length ? frame : (({ interpolation: _, ...last }) => last)(frame));
}

function geckoLibPointVector(point: BbKeyframe["data_points"][number]): MolangVector {
  if (point.x === undefined || point.y === undefined || point.z === undefined) throw new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value.");
  return [point.x, point.y, point.z].map((value) => {
    const scalar = molangScalar(value);
    return typeof scalar === "string" ? GECKOLIB_BBMODEL_TRANSFORMS.runtimeMolang(scalar) : scalar;
  }) as MolangVector;
}
