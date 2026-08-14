import { describe, expect, it } from "vitest";
import { geckoLibEasingProgress, SUPPORTED_GECKOLIB_EASINGS } from "./geckoLibEasing";

describe("geckoLibEasingProgress", () => {
  it("supports every built-in non-spline GeckoLib easing", () => {
    expect(SUPPORTED_GECKOLIB_EASINGS).toHaveLength(33);
    for (const easing of SUPPORTED_GECKOLIB_EASINGS) {
      expect(geckoLibEasingProgress(easing, 0.5), easing).toBeTypeOf("number");
    }
  });

  it("matches representative GeckoLib easing curves", () => {
    expect(geckoLibEasingProgress("linear", 0.5)).toBe(0.5);
    expect(geckoLibEasingProgress("easeInQuad", 0.5)).toBe(0.25);
    expect(geckoLibEasingProgress("easeOutCirc", 0.5)).toBeCloseTo(Math.sqrt(0.75));
    expect(geckoLibEasingProgress("easeInOutCubic", 0.25)).toBe(0.0625);
  });

  it("returns undefined for custom or unknown easing names", () => {
    expect(geckoLibEasingProgress("customEasing", 0.5)).toBeUndefined();
  });
});
