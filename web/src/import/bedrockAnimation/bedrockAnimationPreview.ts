import type { PreviewProjection } from "../../domain/previewProjection";
import { matrix4ToRowMajor } from "../../format/matrix";
import { TICKS_PER_SECOND } from "../../format/time";
import type { BedrockAnimation } from "./bedrockAnimationSchema";
import { evaluateApproximateBedrockChannel } from "./bedrockAnimationBaker";
import {
  BEDROCK_PLAYER_SLICES,
  buildBedrockPlayerWorldMatrices,
  resolveBedrockPlayerBone,
  type BedrockPlayerTransform,
} from "./bedrockPlayerRig";

export function createBedrockAnimationPreview(
  name: string,
  animation: BedrockAnimation,
  animationDurationTicks: number,
  playbackRate: number,
  startDelayTicks: number,
): PreviewProjection {
  const durationTicks = animationDurationTicks + startDelayTicks;
  const ticks = approximatePreviewTicks(animation, animationDurationTicks, playbackRate);
  const stepTicks = approximateStepTicks(animation, playbackRate);
  const tracks: PreviewProjection["tracks"] = Object.fromEntries(BEDROCK_PLAYER_SLICES.map((slice) => [slice.id, {
    transforms: [],
    visibility: [],
  }]));
  if (startDelayTicks > 0) {
    const bindMatrices = buildBedrockPlayerWorldMatrices(new Map());
    for (const slice of BEDROCK_PLAYER_SLICES) {
      tracks[slice.id].transforms.push({
        tick: 0,
        matrix: matrix4ToRowMajor(bindMatrices.get(slice.bone.id)!, `${name}/${slice.id}/0`),
        interpolation: { type: "step" },
      });
    }
  }
  for (const [tickIndex, tick] of ticks.entries()) {
    const sourceTime = tick / TICKS_PER_SECOND * playbackRate;
    const outputTick = tick + startDelayTicks;
    const worldMatrices = buildBedrockPlayerWorldMatrices(collectApproximateTransforms(name, animation, sourceTime));
    const previousTick = ticks[tickIndex - 1];
    for (const slice of BEDROCK_PLAYER_SLICES) {
      const matrix = worldMatrices.get(slice.bone.id);
      if (!matrix) throw new Error(`Missing animated matrix for Bedrock player bone ${slice.bone.id}.`);
      tracks[slice.id].transforms.push({
        tick: outputTick,
        matrix: matrix4ToRowMajor(matrix, `${name}/${slice.id}/${outputTick}`),
        interpolation: tick === 0 || stepTicks.has(tick)
          ? { type: "step" }
          : { type: "linear", durationTicks: Math.max(1, tick - previousTick) },
      });
    }
  }
  return { durationTicks, tracks, availability: { status: "full" } };
}

function collectApproximateTransforms(name: string, animation: BedrockAnimation, time: number): Map<string, BedrockPlayerTransform> {
  const transforms = new Map<string, BedrockPlayerTransform>();
  for (const [sourceBoneName, sourceBone] of Object.entries(animation.bones ?? {})) {
    const bone = resolveBedrockPlayerBone(sourceBoneName);
    if (!bone) continue;
    transforms.set(bone.id, {
      position: evaluateApproximateBedrockChannel(sourceBone.position, time, [0, 0, 0], `${name}.${sourceBoneName}.position`),
      rotation: evaluateApproximateBedrockChannel(sourceBone.rotation, time, [0, 0, 0], `${name}.${sourceBoneName}.rotation`),
      scale: evaluateApproximateBedrockChannel(sourceBone.scale, time, [1, 1, 1], `${name}.${sourceBoneName}.scale`),
    });
  }
  return transforms;
}

function approximatePreviewTicks(animation: BedrockAnimation, durationTicks: number, playbackRate: number): number[] {
  const ticks = new Set<number>([0, durationTicks]);
  const stride = Math.max(1, Math.ceil(durationTicks / 199));
  for (let tick = 0; tick <= durationTicks; tick += stride) ticks.add(tick);
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (typeof channel !== "object" || channel === null || Array.isArray(channel)) continue;
      for (const time of Object.keys(channel)) ticks.add(Math.max(0, Math.min(durationTicks, Math.round(Number(time) / playbackRate * TICKS_PER_SECOND))));
    }
  }
  return [...ticks].sort((first, second) => first - second);
}

function approximateStepTicks(animation: BedrockAnimation, playbackRate: number): Set<number> {
  const ticks = new Set<number>();
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (typeof channel !== "object" || channel === null || Array.isArray(channel)) continue;
      for (const [time, frame] of Object.entries(channel)) {
        if (typeof frame !== "object" || frame === null || Array.isArray(frame) || !("pre" in frame) || !("post" in frame)) continue;
        if (JSON.stringify(frame.pre) !== JSON.stringify(frame.post)) ticks.add(Math.round(Number(time) / playbackRate * TICKS_PER_SECOND));
      }
    }
  }
  return ticks;
}
