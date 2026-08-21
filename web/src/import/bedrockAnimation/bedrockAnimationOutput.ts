import type { EmoteNode, EmoteNodeTracks, EmoteVectorKeyframe, MolangScalar } from "../../format/emoteAnimation";
import { formatMinecraftTime } from "../../format/time";
import type { ImportedAnimation } from "../../domain/conversionSeed";
import { bedrockPositionToCanonical, bedrockRotationToCanonical } from "../coordinateSpace";
import type { BedrockAnimation, BedrockChannel, BedrockExpression, BedrockKeyframe, BedrockKeyframeValue, BedrockVector } from "./bedrockAnimationSchema";
import { BEDROCK_PLAYER_BONES, BEDROCK_PLAYER_RENDER_SCALE, resolveBedrockPlayerBone } from "./bedrockPlayerRig";

const ZERO: readonly [number, number, number] = [0, 0, 0];
const ONE: readonly [number, number, number] = [1, 1, 1];

export function createBedrockRuntime(animation: BedrockAnimation, durationTicks: number, playbackRate: number | null): NonNullable<ImportedAnimation["runtime"]> {
  const timelineRate = playbackRate ?? 1;
  const nodes: Record<string, EmoteNode> = {
    bedrock_scene: { type: "anchor", space: "initiator", transform: { position: ZERO, rotation: ZERO, scale: [BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE] } },
  };
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const bone of BEDROCK_PLAYER_BONES) {
    const source = Object.entries(animation.bones ?? {}).find(([name]) => resolveBedrockPlayerBone(name)?.id === bone.id)?.[1];
    const parent = bone.parent ? `${bone.parent}_x` : "bedrock_scene";
    const parentPivot = BEDROCK_PLAYER_BONES.find((candidate) => candidate.id === bone.parent)?.pivot ?? ZERO;
    const basePosition = bedrockPositionToCanonical(
      bone.pivot.map((value, axis) => value - parentPivot[axis]),
      (value) => -value,
    ).map((value) => value / 16) as [number, number, number];
    nodes[`${bone.id}_z`] = { type: "anchor", parent, transform: { position: basePosition, rotation: ZERO, scale: ONE } };
    nodes[`${bone.id}_y`] = { type: "anchor", parent: `${bone.id}_z`, transform: { position: ZERO, rotation: ZERO, scale: ONE } };
    nodes[`${bone.id}_x`] = { type: "anchor", parent: `${bone.id}_y`, transform: { position: ZERO, rotation: ZERO, scale: ONE } };
    if (bone.cube) {
      nodes[bone.id] = {
        type: "item_display",
        parent: `${bone.id}_x`,
        transform: { position: ZERO, rotation: ZERO, scale: ONE },
        item_stack_snbt: '{id:"minecraft:player_head",count:1}',
        item_display: "none",
      };
    }
    if (!source) continue;
    const position = convertChannel(source.position, basePosition, timelineRate, playbackRate, (values) =>
      bedrockPositionToCanonical(values, negate).map((value, axis) => affine(value, 1 / 16, basePosition[axis])) as MolangVector);
    const rotation = convertChannel(source.rotation, ZERO, timelineRate, playbackRate, (values) => bedrockRotationToCanonical(values, negate));
    const scale = convertChannel(source.scale, ONE, timelineRate, playbackRate, (values) => values);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateAxis(rotation, 2) };
      tracks[`${bone.id}_y`] = { rotation: isolateAxis(rotation, 1) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateAxis(rotation, 0) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
  }
  const molang = playbackRate === null && animation.anim_time_update !== undefined
    ? {
        initialize: "v.bedrock_anim_time = 0;",
        tick: `v.bedrock_anim_time = (${rewriteProgramExpression(animation.anim_time_update)});`,
      }
    : undefined;
  return { ...(molang ? { molang } : {}), nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

type MolangVector = [MolangScalar, MolangScalar, MolangScalar];

function convertChannel(
  channel: BedrockChannel | undefined,
  fallback: readonly [number, number, number],
  timelineRate: number,
  expressionRate: number | null,
  transform: (values: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  if (channel === undefined) return undefined;
  if (!isKeyframed(channel)) return [{ time: "0t", value: transform(vector(channel, expressionRate)) }];
  const result: EmoteVectorKeyframe[] = Object.entries(channel).sort(([first], [second]) => Number(first) - Number(second)).map(([time, keyframe]) => {
    const tick = Math.max(0, Math.round(Number(time) / timelineRate * 20));
    if (!isKeyframeValue(keyframe)) return { time: formatMinecraftTime(tick), value: transform(vector(keyframe, expressionRate)) };
    const pre = keyframe.pre ?? keyframe.post;
    const post = keyframe.post ?? keyframe.pre;
    return { time: formatMinecraftTime(tick), pre: transform(vector(pre!, expressionRate)), post: transform(vector(post!, expressionRate)) };
  });
  const unique: EmoteVectorKeyframe[] = [...new Map(result.map((frame) => [frame.time, frame])).values()];
  if (unique[0]?.time !== "0t") unique.unshift({ time: "0t", value: transform([...fallback] as MolangVector) });
  return unique.map((frame, index) => index + 1 < unique.length ? { ...frame, interpolation: "linear" } : frame);
}

function isolateAxis(frames: EmoteVectorKeyframe[], axis: number): EmoteVectorKeyframe[] {
  const isolate = (values: readonly MolangScalar[]): MolangVector => values.map((value, index) => index === axis ? value : 0) as MolangVector;
  return frames.map((frame) => ({
    ...frame,
    ...(frame.value ? { value: isolate(frame.value) } : {}),
    ...(frame.pre ? { pre: isolate(frame.pre) } : {}),
    ...(frame.post ? { post: isolate(frame.post) } : {}),
  }));
}

function vector(value: BedrockVector, playbackRate: number | null): MolangVector {
  const values = Array.isArray(value) ? value : [value];
  const expanded = values.length === 1 ? [values[0], values[0], values[0]] : values;
  return expanded.map((entry) => rewriteExpression(entry, playbackRate)) as MolangVector;
}

function rewriteExpression(value: BedrockExpression, playbackRate: number | null): MolangScalar {
  if (typeof value !== "string") return value;
  if (playbackRate === null) return value.trim().replace(/(?:q|query)\.anim_time\b/gi, "v.bedrock_anim_time");
  if (playbackRate === 1) return value.trim();
  return value.trim()
    .replace(/(?:q|query)\.anim_time\b/gi, `(q.anim_time * ${playbackRate})`)
    .replace(/(?:q|query)\.delta_time\b/gi, `(q.delta_time * ${playbackRate})`);
}

function rewriteProgramExpression(value: BedrockExpression): string {
  return String(value).trim().replace(/(?:q|query)\.anim_time\b/gi, "v.bedrock_anim_time");
}

function negate(value: MolangScalar): MolangScalar {
  return typeof value === "number" ? -value : `-(${value})`;
}

function affine(value: MolangScalar, factor: number, offset: number): MolangScalar {
  if (typeof value === "number") return value * factor + offset;
  const scaled = factor === 1 ? `(${value})` : `((${value}) * ${factor})`;
  return offset === 0 ? scaled : `(${scaled} + ${offset})`;
}

function isKeyframed(channel: BedrockChannel): channel is Record<string, BedrockKeyframe> {
  return typeof channel === "object" && channel !== null && !Array.isArray(channel);
}

function isKeyframeValue(value: BedrockKeyframe): value is BedrockKeyframeValue {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
