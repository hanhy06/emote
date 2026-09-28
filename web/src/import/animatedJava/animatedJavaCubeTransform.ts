import type { CubeProjectTransformConvention } from "../common/blockbenchCubeTransform";
import { negatePlayerRotationQueries } from "../../format/molang/runtimeAnalysis";

function projectPosition<T>(values: readonly T[]): [T, T, T] {
  return [values[0], values[1], values[2]];
}

export const ANIMATED_JAVA_BLUEPRINT_TRANSFORMS: CubeProjectTransformConvention = {
  id: "animated_java_blueprint",
  runtimeMolang: negatePlayerRotationQueries,
  position: projectPosition,
  rotation: projectPosition,
  bounds(from, to) {
    return { from: [from[0], from[1], from[2]], to: [to[0], to[1], to[2]] };
  },
};
