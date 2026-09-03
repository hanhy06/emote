import type { RuntimeNode, RuntimeNodeTracks } from "../../domain/minecraftData";
import type { EmoteVectorKeyframe, MolangScalar } from "../../format/emoteAnimation";
import { formatMinecraftTime } from "../../format/time";
import type { ImportedAnimation } from "../../domain/conversionSeed";
import { bedrockPositionToCanonical, bedrockRotationToCanonical } from "../coordinateSpace";
import { affineMolang, isolateMolangAxis, negateMolang, type MolangVector } from "../molangVector";
import type { BedrockAnimation, BedrockChannel, BedrockExpression, BedrockKeyframe, BedrockKeyframeValue, BedrockVector } from "./bedrockAnimationSchema";
import { BEDROCK_PLAYER_BONES, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_SLICES, resolveBedrockPlayerBone } from "./bedrockPlayerRig";

const ZERO: readonly [number, number, number] = [0, 0, 0];
const ONE: readonly [number, number, number] = [1, 1, 1];

export function createBedrockRuntime(
  animation: BedrockAnimation,
  durationTicks: number,
  playbackRate: number | null,
  startDelayTicks: number,
): NonNullable<ImportedAnimation["runtime"]> {
  const timelineRate = playbackRate ?? 1;
  const nodes: Record<string, RuntimeNode> = {
    bedrock_scene: { type: "anchor", space: "initiator", transform: { position: ZERO, rotation: ZERO, scale: [BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE] } },
  };
  const tracks: Record<string, RuntimeNodeTracks> = {};
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
    for (const slice of BEDROCK_PLAYER_SLICES.filter((candidate) => candidate.bone.id === bone.id)) {
      nodes[slice.id] = {
        type: "item_display",
        parent: `${bone.id}_x`,
        transform: { position: ZERO, rotation: ZERO, scale: ONE },
        itemStack: { id: "minecraft:player_head", count: 1 },
        item_display: "none",
      };
    }
    if (!source) continue;
    const position = convertChannel(source.position, basePosition, timelineRate, playbackRate, startDelayTicks, (values) =>
      bedrockPositionToCanonical(values, negateMolang).map((value, axis) => affineMolang(value, 1 / 16, basePosition[axis])) as MolangVector);
    const rotation = convertChannel(source.rotation, ZERO, timelineRate, playbackRate, startDelayTicks, (values) => bedrockRotationToCanonical(values, negateMolang));
    const scale = convertChannel(source.scale, ONE, timelineRate, playbackRate, startDelayTicks, (values) => values);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateMolangAxis(rotation, 2) };
      tracks[`${bone.id}_y`] = { rotation: isolateMolangAxis(rotation, 1) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateMolangAxis(rotation, 0) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
  }
  const molang = playbackRate === null && animation.anim_time_update !== undefined
    ? {
        initialize: "v.bedrock_anim_time = 0;",
        tick: startDelayTicks === 0
          ? `v.bedrock_anim_time = (${rewriteProgramExpression(animation.anim_time_update)});`
          : `v.bedrock_anim_time = q.anim_time < ${startDelayTicks / 20} ? 0 : (${rewriteProgramExpression(animation.anim_time_update)});`,
      }
    : undefined;
  return { ...(molang ? { molang } : {}), nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

function convertChannel(
  channel: BedrockChannel | undefined,
  fallback: readonly [number, number, number],
  timelineRate: number,
  expressionRate: number | null,
  startDelayTicks: number,
  transform: (values: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  if (channel === undefined) return undefined;
  if (!isKeyframed(channel)) {
    const result: EmoteVectorKeyframe[] = [{ time: formatMinecraftTime(startDelayTicks), value: transform(vector(channel, expressionRate, startDelayTicks)) }];
    if (startDelayTicks > 0) result.unshift({ time: "0t", value: transform([...fallback] as MolangVector), interpolation: "step" });
    return result;
  }
  const result: EmoteVectorKeyframe[] = Object.entries(channel).sort(([first], [second]) => Number(first) - Number(second)).map(([time, keyframe]) => {
    const tick = startDelayTicks + Math.max(0, Math.round(Number(time) / timelineRate * 20));
    if (!isKeyframeValue(keyframe)) return { time: formatMinecraftTime(tick), value: transform(vector(keyframe, expressionRate, startDelayTicks)) };
    const pre = keyframe.pre ?? keyframe.post;
    const post = keyframe.post ?? keyframe.pre;
    return { time: formatMinecraftTime(tick), pre: transform(vector(pre!, expressionRate, startDelayTicks)), post: transform(vector(post!, expressionRate, startDelayTicks)) };
  });
  const unique: EmoteVectorKeyframe[] = [...new Map(result.map((frame) => [frame.time, frame])).values()];
  if (unique[0]?.time !== "0t") unique.unshift({ time: "0t", value: transform([...fallback] as MolangVector), interpolation: "step" });
  return unique.map((frame, index) => index + 1 < unique.length ? { ...frame, interpolation: frame.interpolation ?? "linear" } : frame);
}

function vector(value: BedrockVector, playbackRate: number | null, startDelayTicks: number): MolangVector {
  const values = Array.isArray(value) ? value : [value];
  const expanded = values.length === 1 ? [values[0], values[0], values[0]] : values;
  return expanded.map((entry) => rewriteExpression(entry, playbackRate, startDelayTicks)) as MolangVector;
}

function rewriteExpression(value: BedrockExpression, playbackRate: number | null, startDelayTicks: number): MolangScalar {
  if (typeof value !== "string") return value;
  if (playbackRate === null) return value.trim().replace(/(?:q|query)\.anim_time\b/gi, "v.bedrock_anim_time");
  const animationTime = startDelayTicks === 0 ? "q.anim_time" : `(math.max(0, q.anim_time - ${startDelayTicks / 20}))`;
  return value.trim()
    .replace(/(?:q|query)\.anim_time\b/gi, playbackRate === 1 ? animationTime : `(${animationTime} * ${playbackRate})`)
    .replace(/(?:q|query)\.delta_time\b/gi, playbackRate === 1 ? "q.delta_time" : `(q.delta_time * ${playbackRate})`);
}

function rewriteProgramExpression(value: BedrockExpression): string {
  return String(value).trim().replace(/(?:q|query)\.anim_time\b/gi, "v.bedrock_anim_time");
}

function isKeyframed(channel: BedrockChannel): channel is Record<string, BedrockKeyframe> {
  return typeof channel === "object" && channel !== null && !Array.isArray(channel);
}

function isKeyframeValue(value: BedrockKeyframe): value is BedrockKeyframeValue {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
