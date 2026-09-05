import { describe, expect, it } from "vitest";
import { ANIMATED_JAVA_BLUEPRINT_TRANSFORMS, GECKOLIB_BBMODEL_TRANSFORMS } from "./cubeProjectTransformConvention";

describe("cube project transform conventions", () => {
  it("keeps GeckoLib project positions, rotations, and bounds in their saved axes", () => {
    expect(GECKOLIB_BBMODEL_TRANSFORMS.position([1, 2, 3], (value) => -value)).toEqual([1, 2, 3]);
    expect(GECKOLIB_BBMODEL_TRANSFORMS.rotation([10, 20, 30], (value) => -value)).toEqual([10, 20, 30]);
    expect(GECKOLIB_BBMODEL_TRANSFORMS.bounds([1, 2, 3], [4, 5, 6])).toEqual({ from: [1, 2, 3], to: [4, 5, 6] });
  });

  it("keeps Animated Java bone project axes independent from its display-element convention", () => {
    expect(ANIMATED_JAVA_BLUEPRINT_TRANSFORMS.position([1, 2, 3], (value) => -value)).toEqual([1, 2, 3]);
    expect(ANIMATED_JAVA_BLUEPRINT_TRANSFORMS.rotation([10, 20, 30], (value) => -value)).toEqual([10, 20, 30]);
    expect(ANIMATED_JAVA_BLUEPRINT_TRANSFORMS.bounds([1, 2, 3], [4, 5, 6])).toEqual({ from: [1, 2, 3], to: [4, 5, 6] });
  });
});
