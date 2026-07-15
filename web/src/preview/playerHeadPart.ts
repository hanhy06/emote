export interface Vector3Value {
  x: number;
  y: number;
  z: number;
}

export interface PlayerHeadPart {
  partIndex: number;
  matrix: readonly number[];
  anchor: Vector3Value;
}

export function createPlayerHeadPart(partIndex: number, matrix: readonly number[]): PlayerHeadPart {
  return {
    partIndex,
    matrix,
    anchor: {
      x: matrix[3] - matrix[1] * 0.25,
      y: matrix[7] - matrix[5] * 0.25,
      z: matrix[11] - matrix[9] * 0.25,
    },
  };
}
