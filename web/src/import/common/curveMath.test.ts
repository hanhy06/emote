import { describe, expect, it } from "vitest";
import { easingProgress } from "./curveMath";

describe("curve math", () => {
  it("evaluates easing independently of an import format", () => {
    expect(easingProgress("easeinquad", 0.5)).toBe(0.25);
    expect(easingProgress("step", 0.74, [4])).toBe(0.5);
  });
});
