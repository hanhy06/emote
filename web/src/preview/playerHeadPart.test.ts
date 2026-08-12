import { describe, expect, it } from "vitest";
import { isVisibleAtTick } from "./playerHeadPart";

describe("isVisibleAtTick", () => {
  const track = {
    transforms: [],
    visibility: [
      { tick: 4, visible: false },
      { tick: 8, visible: true },
    ],
  };

  it("uses the node default before its first visibility keyframe", () => {
    expect(isVisibleAtTick(true, track, null)).toBe(true);
    expect(isVisibleAtTick(true, track, 2)).toBe(true);
  });

  it("uses the latest visibility state at the preview tick", () => {
    expect(isVisibleAtTick(true, track, 4)).toBe(false);
    expect(isVisibleAtTick(true, track, 8)).toBe(true);
  });
});
