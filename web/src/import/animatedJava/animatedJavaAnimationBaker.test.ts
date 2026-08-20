import { describe, expect, it } from "vitest";
import { bakeAjNodeChannels, type AjTransformValues } from "./animatedJavaAnimationBaker";
import type { AjNodeChannels } from "./animatedJavaSchema";

const BASE: AjTransformValues = {
  position: [0, 0, 0],
  rotation: [0, 0, 0],
  scale: [1, 1, 1],
};

describe("Animated Java animation baker", () => {
  it("inverts Bezier time and evaluates the curve without sampled points", () => {
    const interpolation = {
      type: "bezier" as const,
      left_handle_time: [-1 / 30, 0, 0],
      left_handle_value: [-1, 0, 0],
      right_handle_time: [1 / 30, 0, 0],
      right_handle_value: [1, 0, 0],
    };
    const channels: AjNodeChannels = { position: {
      "0": { value: ["0", "0", "0"], interpolation },
      "0.1": { value: ["3", "0", "0"], interpolation },
    } };

    const frames = bakeAjNodeChannels(channels, BASE, 2, "test/bezier");

    expect(frames[1].position[0]).toBeCloseTo(1.5, 8);
  });

  it("evaluates Catmull-Rom directly from neighboring vectors", () => {
    const catmullrom = { type: "catmullrom" as const };
    const channels: AjNodeChannels = { position: {
      "0": { value: ["0", "0", "0"], interpolation: catmullrom },
      "0.1": { value: ["1", "0", "0"], interpolation: catmullrom },
      "0.2": { value: ["0", "0", "0"], interpolation: catmullrom },
    } };

    const frames = bakeAjNodeChannels(channels, BASE, 4, "test/catmullrom");

    expect(frames[1].position[0]).toBeCloseTo(0.5625, 8);
  });
});
