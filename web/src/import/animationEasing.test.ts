import { describe, expect, it } from "vitest";
import { animationEasingProgress, SUPPORTED_BLOCKBENCH_EASINGS } from "./animationEasing";

describe("animationEasingProgress", () => {
  it("supports every built-in non-spline GeckoLib easing", () => {
    for (const easing of SUPPORTED_BLOCKBENCH_EASINGS) {
      expect(animationEasingProgress(easing, 0.5), easing).toBeTypeOf("number");
    }
  });

  it("matches representative GeckoLib easing curves", () => {
    expect(animationEasingProgress("linear", 0.5)).toBe(0.5);
    expect(animationEasingProgress("easeInQuad", 0.5)).toBe(0.25);
    expect(animationEasingProgress("easeOutCirc", 0.5)).toBeCloseTo(Math.sqrt(0.75));
    expect(animationEasingProgress("easeInOutCubic", 0.25)).toBe(0.0625);
  });

  it("returns undefined for custom or unknown easing names", () => {
    expect(animationEasingProgress("customEasing", 0.5)).toBeUndefined();
  });
});
