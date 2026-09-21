import type { AnimationRuntimeData } from "../../domain/runtimeProjection";
import type { EmotecraftAnimationSamples } from "./emotecraftAnimationSampling";

export function projectEmotecraftRuntime(samples: EmotecraftAnimationSamples): Extract<AnimationRuntimeData, { kind: "baked" }> {
  return {
    kind: "baked",
    tracks: Object.fromEntries(Object.entries(samples.transforms).map(([nodeId, frames]) => [nodeId, {
      transforms: frames.map((frame) => ({
        tick: frame.tick,
        matrix: frame.matrix,
        interpolation: frame.step ? { type: "step" } : { type: "linear", durationTicks: 1 },
      })),
      visibility: [],
      nbt: [],
    }])),
  };
}
