import { describe, expect, it } from "vitest";
import { MAX_ANIMATION_DURATION_TICKS, requireAnimationDurationTicks, requireTick, secondsToTicks } from "./time";

describe("time utilities", () => {
  it("converts exact 20 TPS times to integer ticks", () => {
    expect(secondsToTicks(0, "start")).toBe(0);
    expect(secondsToTicks(0.05, "keyframe")).toBe(1);
    expect(secondsToTicks(1.5, "duration")).toBe(30);
  });

  it("rejects fractional and invalid ticks", () => {
    expect(() => secondsToTicks(0.01, "keyframe")).toThrow("does not fall on a 20 TPS tick");
    expect(() => requireTick(1.5, "keyframe")).toThrow("must be a non-negative integer tick");
  });

  it("limits animation duration to ten minutes", () => {
    expect(requireAnimationDurationTicks(MAX_ANIMATION_DURATION_TICKS, "duration")).toBe(12_000);
    expect(() => requireAnimationDurationTicks(0, "duration")).toThrow("between 1 and 12000 ticks");
    expect(() => requireAnimationDurationTicks(MAX_ANIMATION_DURATION_TICKS + 1, "duration")).toThrow("between 1 and 12000 ticks");
  });
});
