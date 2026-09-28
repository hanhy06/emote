export interface CubeProjectTransformConvention {
  readonly id: string;
  runtimeMolang(expression: string): string;
  position<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  rotation<T>(values: readonly T[], negate: (value: T) => T): [T, T, T];
  bounds(from: readonly number[], to: readonly number[]): { from: [number, number, number]; to: [number, number, number] };
}
