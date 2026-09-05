export interface CubeProjectTransformConvention {
  readonly id: "geckolib_bbmodel" | "animated_java_blueprint";
  position<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  rotation<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  bounds(from: readonly number[], to: readonly number[]): { from: [number, number, number]; to: [number, number, number] };
}
