import { Matrix4 } from "three";
import { describe, expect, it } from "vitest";
import { IDENTITY_MATRIX, asMatrix16, matrix4ToRowMajor } from "./matrix";

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
});
