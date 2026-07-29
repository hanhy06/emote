import { Matrix4 } from "three";
import { describe, expect, it } from "vitest";
import { IDENTITY_MATRIX, asMatrix16, matrix4ToRowMajor, stabilizeDisplayMatrix } from "./matrix";

describe("matrix utilities", () => {
  it("converts Three.js matrices to the emote row-major layout", () => {
    const matrix = new Matrix4().makeTranslation(1, 2, 3);

    expect(matrix4ToRowMajor(matrix, "test matrix")).toEqual([
      1, 0, 0, 1,
      0, 1, 0, 2,
      0, 0, 1, 3,
      0, 0, 0, 1,
    ]);
    expect(IDENTITY_MATRIX).toEqual(asMatrix16(new Matrix4().identity().elements, "identity"));
  });

  it("rejects malformed matrices", () => {
    expect(() => asMatrix16([1, 2, 3], "short matrix")).toThrow("short matrix must contain 16 finite numbers");
  });

  it("removes shear while preserving translation", () => {
    const result = stabilizeDisplayMatrix([
      1, 0.25, 0, 4,
      0, 1, 0.2, 5,
      0, 0, 0.5, 6,
      0, 0, 0, 1,
    ], "sheared matrix");
    const columns = [
      [result[0], result[4], result[8]],
      [result[1], result[5], result[9]],
      [result[2], result[6], result[10]],
    ];

    expect(result.slice(3, 12).filter((_, index) => index % 4 === 0)).toEqual([4, 5, 6]);
    expect(normalizedDot(columns[0], columns[1])).toBeCloseTo(0, 8);
    expect(normalizedDot(columns[0], columns[2])).toBeCloseTo(0, 8);
    expect(normalizedDot(columns[1], columns[2])).toBeCloseTo(0, 8);
  });

  it("does not change matrices that already contain stable TRS", () => {
    const matrix = [
      0, -2, 0, 1,
      3, 0, 0, 2,
      0, 0, 4, 3,
      0, 0, 0, 1,
    ];

    expect(stabilizeDisplayMatrix(matrix, "stable matrix")).toEqual(matrix);
  });
});

function normalizedDot(first: number[], second: number[]): number {
  const dot = first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
  return dot / (Math.hypot(...first) * Math.hypot(...second));
}
