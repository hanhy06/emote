import type { PreviewProjection } from "../../domain/previewProjection";
import type { EmotecraftAnimationSamples } from "./emotecraftAnimationSampling";

export function projectEmotecraftPreview(samples: EmotecraftAnimationSamples): PreviewProjection {
  return {
    durationTicks: samples.durationTicks,
    availability: { status: "full" },
    tracks: Object.fromEntries(Object.entries(samples.transforms).map(([nodeId, frames]) => [nodeId, {
      transforms: frames.map((frame) => ({
        tick: frame.tick,
        matrix: frame.matrix,
        interpolation: frame.step ? { type: "step" } : { type: "linear", durationTicks: 1 },
      })),
      visibility: [],
    }])),
  };
}
