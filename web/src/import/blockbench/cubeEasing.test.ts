import { describe, expect, it } from "vitest";
import { cubeEasingProgress, SUPPORTED_GECKOLIB_EASINGS } from "./cubeEasing";

describe("cubeEasingProgress", () => {
  it("supports every built-in non-spline GeckoLib easing", () => {
    for (const easing of SUPPORTED_GECKOLIB_EASINGS) {
      expect(cubeEasingProgress(easing, 0.5), easing).toBeTypeOf("number");
    }
  });

  it("matches representative GeckoLib easing curves", () => {
    expect(cubeEasingProgress("linear", 0.5)).toBe(0.5);
    expect(cubeEasingProgress("easeInQuad", 0.5)).toBe(0.25);
    expect(cubeEasingProgress("easeOutCirc", 0.5)).toBeCloseTo(Math.sqrt(0.75));
    expect(cubeEasingProgress("easeInOutCubic", 0.25)).toBe(0.0625);
  });

  it("returns undefined for custom or unknown easing names", () => {
    expect(cubeEasingProgress("customEasing", 0.5)).toBeUndefined();
  });
});
