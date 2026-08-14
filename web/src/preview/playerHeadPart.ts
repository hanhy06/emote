import type { ImportedNodeTrack } from "../domain/conversionSeed";

export interface PlayerHeadPart {
  nodeId: string;
  partIndex: number;
  matrix: readonly number[];
  conversionMatrix?: readonly number[];
}

export function isVisibleAtTick(defaultVisible: boolean, track: ImportedNodeTrack | undefined, tick: number | null): boolean {
  if (tick === null) return defaultVisible;
  return track?.visibility.filter((keyframe) => keyframe.tick <= tick).at(-1)?.visible ?? defaultVisible;
}
