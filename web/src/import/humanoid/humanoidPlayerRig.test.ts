import { describe, expect, it } from "vitest";
import { humanoidSkinSlices } from "./humanoidPlayerRig";

describe("humanoidSkinSlices", () => {
  it("keeps the head whole", () => {
    expect(humanoidSkinSlices("head", false)).toEqual([
      { order: 0, startY: 0, endY: 8, motion: "upper" },
    ]);
  });

  it("splits rigid body parts into four and eight pixel regions", () => {
    expect(humanoidSkinSlices("left_arm", false)).toEqual([
      { order: 0, startY: 0, endY: 4, motion: "upper" },
      { order: 1, startY: 4, endY: 12, motion: "upper" },
    ]);
  });

  it("assigns the lower body slice to bend motion", () => {
    expect(humanoidSkinSlices("body", true).map((slice) => [slice.startY, slice.endY, slice.motion])).toEqual([
      [0, 4, "upper"],
      [4, 12, "lower"],
    ]);
  });

  it("splits jointed limbs around the elbow or knee", () => {
    expect(humanoidSkinSlices("right_leg", true).map((slice) => [slice.startY, slice.endY, slice.motion])).toEqual([
      [0, 4, "upper"],
      [4, 6, "upper"],
      [6, 8, "lower"],
      [8, 12, "lower"],
    ]);
  });
});

