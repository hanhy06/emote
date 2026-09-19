import { describe, expect, it } from "vitest";
import type { BbKeyframe } from "./blockbenchCubeSchema";
import { evaluateApproximateBlockbenchChannel } from "./blockbenchKeyframeEvaluator";

const frame = (time: number, x: number | string, interpolation = "linear", easing?: string): BbKeyframe => ({
  channel: "rotation",
  time,
  interpolation,
  ...(easing ? { easing } : {}),
  data_points: [{ x, y: 0, z: 0 }],
});

describe("approximate Blockbench preview evaluation", () => {
  it("evaluates deterministic time-based math", () => {
    const frames = [frame(0, "math.sin(q.anim_time * 180)")];

    expect(evaluateApproximateBlockbenchChannel(frames, "rotation", 0.5, [0, 0, 0], "preview")[0]).toBeCloseTo(1);
  });

  it("uses linear and outgoing step interpolation while ignoring easing", () => {
    expect(evaluateApproximateBlockbenchChannel([frame(0, 0), frame(1, 10, "bezier", "easeOutCirc")], "rotation", 0.5, [0, 0, 0], "preview")[0]).toBe(5);
    expect(evaluateApproximateBlockbenchChannel([frame(0, 2, "step"), frame(1, 10)], "rotation", 0.5, [0, 0, 0], "preview")[0]).toBe(2);
  });

  it("falls back for runtime state, variables, and nondeterministic math", () => {
    for (const expression of ["q.target_x_rotation", "v.speed", "math.random(0, 1)"]) {
      expect(evaluateApproximateBlockbenchChannel([frame(0, expression)], "rotation", 0, [7, 0, 0], "preview")[0]).toBe(7);
    }
  });
});
