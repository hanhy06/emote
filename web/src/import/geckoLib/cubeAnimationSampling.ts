import type { Matrix4 } from "three";
import type { BbAnimation, BbKeyframe } from "./cubeProjectSchema";
import { evaluateGeckoChannel } from "./cubeAnimationBaker";
import { planAnimationAnchorSamples, type AnimationAnchor, type AnimationSamplePlan } from "../animationSampling";

export function planAnimationSamples(
  animation: BbAnimation,
  animationIndex: number,
  durationTicks: number,
  boneCount: number,
  snapshotAt: (time: number) => Matrix4[],
): AnimationSamplePlan {
  const anchors = collectAnimationAnchors(animation, animationIndex);
  return planAnimationAnchorSamples(anchors, durationTicks, boneCount, snapshotAt);
}

function collectAnimationAnchors(animation: BbAnimation, animationIndex: number): AnimationAnchor[] {
  const anchors = new Map<string, AnimationAnchor>();
  const addAnchor = (time: number, priority: number, step: boolean) => {
    if (!Number.isFinite(time) || time < 0 || time > animation.length) return;
    const key = time.toFixed(6);
    const current = anchors.get(key);
    if (!current) anchors.set(key, { time, priority, step });
    else {
      current.priority = Math.max(current.priority, priority);
      current.step ||= step;
    }
  };

  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    const keyframes = animator.keyframes ?? [];
    for (const channel of ["position", "rotation", "scale"]) {
      const frames = keyframes.filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
      for (const [frameIndex, frame] of frames.entries()) {
        const discontinuity = frame.data_points.length === 2 || (frameIndex > 0 && frames[frameIndex - 1].interpolation === "step");
        addAnchor(frame.time, discontinuity ? 200 : 100, discontinuity);
      }
      for (let frameIndex = 1; frameIndex < frames.length; frameIndex++) {
        const before = frames[frameIndex - 1];
        const after = frames[frameIndex];
        addEasingFeatureAnchors(before, after, frames, channel, `animations[${animationIndex}].animators.${animatorId}`, addAnchor);
      }
    }
  }
  return [...anchors.values()].sort((first, second) => first.time - second.time);
}

function addEasingFeatureAnchors(
  before: BbKeyframe,
  after: BbKeyframe,
  channelFrames: BbKeyframe[],
  channel: string,
  path: string,
  addAnchor: (time: number, priority: number, step: boolean) => void,
): void {
  const gap = after.time - before.time;
  if (gap <= 0) return;
  const easing = (after.easing ?? "linear").toLowerCase();
  if (easing === "step") {
    const steps = Math.max(2, Math.floor(after.easingArgs?.[0] ?? 5));
    for (let step = 1; step < steps; step++) addAnchor(before.time + gap * step / steps, 160, true);
    return;
  }
  const curvedInterpolation = before.interpolation === "catmullrom" || after.interpolation === "catmullrom"
    || before.interpolation === "bezier" || after.interpolation === "bezier";
  if (!curvedInterpolation && !/(back|elastic|bounce)/.test(easing)) return;

  const fallback = channel === "scale" ? [1, 1, 1] : [0, 0, 0];
  const subdivisions = Math.max(8, Math.min(64, Math.ceil(gap * 240)));
  const samples = Array.from({ length: subdivisions + 1 }, (_, sampleIndex) => {
    const time = before.time + gap * sampleIndex / subdivisions;
    return { time, value: evaluateGeckoChannel(channelFrames, channel, time, fallback, path) };
  });
  for (let sampleIndex = 1; sampleIndex < samples.length - 1; sampleIndex++) {
    const previous = samples[sampleIndex - 1].value;
    const current = samples[sampleIndex].value;
    const next = samples[sampleIndex + 1].value;
    const turns = current.some((value, axis) => {
      const incoming = value - previous[axis];
      const outgoing = next[axis] - value;
      return Math.abs(incoming) > 1e-6 && Math.abs(outgoing) > 1e-6 && incoming * outgoing < 0;
    });
    if (turns) addAnchor(samples[sampleIndex].time, 60, false);
  }
}
