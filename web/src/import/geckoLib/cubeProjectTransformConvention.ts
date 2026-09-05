export interface CubeProjectTransformConvention {
  readonly id: "geckolib_bbmodel" | "animated_java_blueprint";
  position<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  rotation<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  bounds(from: readonly number[], to: readonly number[]): { from: [number, number, number]; to: [number, number, number] };
}

function projectPosition<T>(values: readonly T[]): [T, T, T] {
  return [values[0], values[1], values[2]];
}

function projectBounds(from: readonly number[], to: readonly number[]): { from: [number, number, number]; to: [number, number, number] } {
  return {
    from: [from[0], from[1], from[2]],
    to: [to[0], to[1], to[2]],
  };
}

export const GECKOLIB_BBMODEL_TRANSFORMS: CubeProjectTransformConvention = {
  id: "geckolib_bbmodel",
  position: projectPosition,
  rotation: projectPosition,
  bounds: projectBounds,
};

// Animated Java bones currently use the saved Blockbench axes, but this remains a distinct
// convention because AJ display elements apply their own position and rotation rules.
export const ANIMATED_JAVA_BLUEPRINT_TRANSFORMS: CubeProjectTransformConvention = {
  id: "animated_java_blueprint",
  position: projectPosition,
  rotation: projectPosition,
  bounds: projectBounds,
};
