import type { ImportedTimelineEvent } from "../../domain/conversionSeed";
import type { BbAnimation, BbAnimator } from "./blockbenchCubeSchema";

export type BlockbenchTransformChannel = "position" | "rotation" | "scale";

export interface BlockbenchChannelSampling {
  sourceTimes: ReadonlyMap<number, number>;
  stepTicks: ReadonlySet<number>;
}

export interface BlockbenchAnimationSource {
  animation: BbAnimation;
  animationIndex: number;
  animators: ReadonlyMap<string, BbAnimator>;
  durationTicks: number;
  startDelaySeconds: number;
  startDelayTicks: number;
  blendWeight: number;
  playbackMode: "once" | "hold" | "loop";
  loopDelayTicks: number;
  events: ImportedTimelineEvent[];
  requiresNativeRuntime: boolean;
  channelSampling: ReadonlyMap<string, Readonly<Partial<Record<BlockbenchTransformChannel, BlockbenchChannelSampling>>>>;
}

export function blockbenchChannelSampling(
  source: BlockbenchAnimationSource,
  animatorId: string,
  channel: BlockbenchTransformChannel,
): BlockbenchChannelSampling | undefined {
  return source.channelSampling.get(animatorId)?.[channel];
}
