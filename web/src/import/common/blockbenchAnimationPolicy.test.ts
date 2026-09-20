import { describe, expect, it } from "vitest";
import type { BbKeyframe } from "./blockbenchCubeSchema";
import { ANIMATED_JAVA_CHANNELS } from "../animatedJava/animatedJavaAnimationPolicy";
import { GECKOLIB_CHANNELS } from "../geckoLib/geckoLibAnimationPolicy";

const INVALID_FRAME: BbKeyframe = { channel: "position", time: 0, data_points: [] };
const RUNTIME_FRAME: BbKeyframe = {
  channel: "position",
  time: 0,
  data_points: [{ x: "q.unknown", y: 0, z: 0 }],
};

describe("format-specific Blockbench animation policies", () => {
  it("owns invalid keyframe diagnostics per format", () => {
    expect(() => ANIMATED_JAVA_CHANNELS.evaluate([INVALID_FRAME], "position", 0, [0, 0, 0], "frame"))
      .toThrow(expect.objectContaining({ code: "unsupported_animated_java_keyframe" }));
    expect(() => GECKOLIB_CHANNELS.evaluate([INVALID_FRAME], "position", 0, [0, 0, 0], "frame"))
      .toThrow(expect.objectContaining({ code: "unsupported_geckolib_keyframe" }));
  });

  it("owns runtime Molang fallback per format", () => {
    expect(ANIMATED_JAVA_CHANNELS.canBake([RUNTIME_FRAME], "position", [0, 0, 0], "frame")).toBe(false);
    expect(GECKOLIB_CHANNELS.canBake([RUNTIME_FRAME], "position", [0, 0, 0], "frame")).toBe(false);
  });
});
