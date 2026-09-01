import { describe, expect, it } from "vitest";
import { matrixToLocalTransform } from "./localTransform";

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
});
