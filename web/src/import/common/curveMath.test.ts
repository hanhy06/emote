import { describe, expect, it } from "vitest";
import { easingProgress, sampleBezierAtX, sampleSpline } from "./curveMath";

describe("curve math", () => {
  it("evaluates easing independently of an import format", () => {
    expect(easingProgress("easeinquad", 0.5)).toBe(0.25);
    expect(easingProgress("step", 0.74, [4])).toBe(0.5);
  });

  it("samples spline and Bezier curves using numeric control points", () => {
    expect(sampleSpline([[0, 0], [1, 1]], 0.5)).toBeCloseTo(0.5);
    expect(sampleBezierAtX([[0, 0], [0.25, 0.25], [0.75, 0.75], [1, 1]], 0.5)).toBeCloseTo(0.5);
  });
});
