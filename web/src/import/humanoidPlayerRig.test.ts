import { describe, expect, it } from "vitest";
import { Matrix4, Quaternion, Vector3 } from "three";
import { humanoidJointFillMatrix, humanoidRenderPieces, humanoidSkinSlices } from "./humanoidPlayerRig";

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

  it("adds two joint fillers that reuse the middle limb skin orders", () => {
    expect(humanoidRenderPieces("right_arm", true).map((piece) => [piece.kind, piece.order, piece.motion, piece.jointSide])).toEqual([
      ["slice", 0, "upper", undefined],
      ["slice", 1, "upper", undefined],
      ["joint_fill", 1, "upper", "upper"],
      ["joint_fill", 2, "lower", "lower"],
      ["slice", 2, "lower", undefined],
      ["slice", 3, "lower", undefined],
    ]);
  });

  it("matches the BDEngine default elbow filler pose", () => {
    const base = new Matrix4().compose(new Vector3(), new Quaternion(), new Vector3(0.5, 0.25, 0.5));
    const upperPosition = new Vector3();
    const upperRotation = new Quaternion();
    const upperScale = new Vector3();
    humanoidJointFillMatrix(base, "right_arm", "upper").decompose(upperPosition, upperRotation, upperScale);
    const lowerPosition = new Vector3();
    const lowerRotation = new Quaternion();
    const lowerScale = new Vector3();
    humanoidJointFillMatrix(base, "right_arm", "lower").decompose(lowerPosition, lowerRotation, lowerScale);

    expectVector(upperPosition, [0, -0.04875, 0.0475]);
    expectVector(upperScale, [0.498, 0.2625, 0.445]);
    expectVector(lowerPosition, [-0.003125, 0.015, -0.04625]);
    expectVector(lowerScale, [0.498, 0.26875, 0.4375]);
    expect(upperRotation.angleTo(lowerRotation)).toBeCloseTo(Math.PI / 2);
  });
});

function expectVector(actual: Vector3, expected: readonly [number, number, number]): void {
  expected.forEach((value, axis) => expect(actual.getComponent(axis)).toBeCloseTo(value));
}
