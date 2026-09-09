import { describe, expect, it } from "vitest";
import type { Vec3 } from "./emoteAnimation";
import { localTransformToMatrix, matrixToContinuousLocalTransform, matrixToLocalTransform } from "./localTransform";

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

  it.each([
    { name: "forward turns", source: [0, 90, 180, 270, 360, 450, 540, 630, 720] },
    { name: "reverse turns", source: [0, -90, -180, -270, -360] },
  ])("restores continuous $name from sampled matrices", ({ source }) => {
    let previous: Vec3 = [0, 0, 0];
    const restored = source.map((angle) => {
      const matrix = localTransformToMatrix({ position: [0, 0, 0], rotation: [angle, 0, 0], scale: [1, 1, 1] }, `${angle} degree source`);
      const transform = matrixToContinuousLocalTransform(matrix, previous, `${angle} degree transform`);
      previous = transform.rotation;
      return transform.rotation[0];
    });

    restored.forEach((value, index) => expect(value).toBeCloseTo(source[index], 10));
  });

  it("keeps a continuous Euler branch through a gimbal boundary", () => {
    const source = [[10, 80, 20], [10, 90, 20], [10, 100, 20]] as const;
    let previous: Vec3 = [...source[0]];

    for (const rotation of source) {
      const matrix = localTransformToMatrix({ position: [0, 0, 0], rotation: [...rotation], scale: [1, 1, 1] }, "gimbal source");
      const transform = matrixToContinuousLocalTransform(matrix, previous, "gimbal transform");
      const restored = localTransformToMatrix(transform, "restored gimbal transform");
      restored.forEach((value, index) => expect(value).toBeCloseTo(matrix[index], 10));
      expect(Math.max(...transform.rotation.map((value, axis) => Math.abs(value - previous[axis])))).toBeLessThan(90);
      previous = transform.rotation;
    }
  });
});
