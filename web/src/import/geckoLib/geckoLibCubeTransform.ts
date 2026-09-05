import type { CubeProjectTransformConvention } from "../blockbenchCubeTransform";

function projectPosition<T>(values: readonly T[]): [T, T, T] {
  return [values[0], values[1], values[2]];
}

export const GECKOLIB_BBMODEL_TRANSFORMS: CubeProjectTransformConvention = {
  id: "geckolib_bbmodel",
  position: projectPosition,
  rotation: projectPosition,
  bounds(from, to) {
    return { from: [from[0], from[1], from[2]], to: [to[0], to[1], to[2]] };
  },
};
