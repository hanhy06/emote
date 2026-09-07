import { describe, expect, it } from "vitest";
import { localTransformToMatrix, matrixToLocalTransform } from "./localTransform";

describe("matrixToLocalTransform", () => {
  it("preserves a fully collapsed scale", () => {
    const transform = matrixToLocalTransform([
      0, 0, 0, 0,
      0, 0, 0, 0,
      0, 0, 0, 0,
      0, 0, 0, 1,
    ], "collapsed transform");

    expect(transform).toEqual({ position: [0, 0, 0], rotation: [0, 0, 0], scale: [0, 0, 0] });
  });

  it("preserves the remaining scale when one axis is collapsed", () => {
    const matrix = localTransformToMatrix({
      position: [1.2, 0.48, -0.54],
      rotation: [20, -35, 10],
      scale: [0.00013125, 0.00013125, 0],
    }, "partially collapsed source");

    const transform = matrixToLocalTransform(matrix, "partially collapsed transform");
    const restored = localTransformToMatrix(transform, "restored partially collapsed transform");

    expect(transform.scale).toEqual([0.00013125, 0.00013125, 0]);
    restored.forEach((value, index) => expect(value).toBeCloseTo(matrix[index], 12));
  });
});
