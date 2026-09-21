import type { Matrix16 } from "./matrix";

export interface PreviewProjection {
  durationTicks: number;
  tracks: Record<string, PreviewNodeTrack>;
  availability: PreviewAvailability;
}

export type PreviewAvailability =
  | { status: "full" }
  | { status: "create_pose"; reason: string }
  | { status: "unavailable"; reason: string };

export interface PreviewNodeTrack {
  transforms: PreviewTransformKeyframe[];
  visibility: PreviewVisibilityKeyframe[];
}

export interface PreviewTransformKeyframe {
  tick: number;
  matrix: Matrix16;
  interpolation: PreviewInterpolation;
}

export type PreviewInterpolation =
  | { type: "step" }
  | { type: "linear"; durationTicks?: number };

export interface PreviewVisibilityKeyframe {
  tick: number;
  visible: boolean;
}

export const FULL_PREVIEW: PreviewAvailability = { status: "full" };
