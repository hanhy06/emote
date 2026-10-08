import type { AnimationSamplePlan } from "./animationSampling";
import type { ImportedTimelineEvent } from "../../domain/conversionSeed";
import type { BbAnimation, BbAnimator } from "./blockbenchCubeSchema";
import type { RuntimeScalar } from "../../domain/minecraftData";

export type BlockbenchTransformChannel = "position" | "rotation" | "scale";

export interface BlockbenchAnimationSource {
  animation: BbAnimation;
  animationIndex: number;
  animators: ReadonlyMap<string, BbAnimator>;
  durationTicks: number;
  startDelaySeconds: number;
  startDelayTicks: number;
  blendWeight: RuntimeScalar;
  playbackMode: "once" | "hold" | "loop";
  loopDelayTicks: number;
  events: ImportedTimelineEvent[];
  requiresNativeRuntime: boolean;
  channelSampling: ReadonlyMap<string, Readonly<Partial<Record<BlockbenchTransformChannel, AnimationSamplePlan>>>>;
}

export function blockbenchChannelSampling(
  source: BlockbenchAnimationSource,
  animatorId: string,
  channel: BlockbenchTransformChannel,
): AnimationSamplePlan | undefined {
  return source.channelSampling.get(animatorId)?.[channel];
}
