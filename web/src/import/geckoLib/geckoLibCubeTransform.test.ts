import { describe, expect, it } from "vitest";
import { GECKOLIB_BBMODEL_TRANSFORMS } from "./geckoLibCubeTransform";

describe("cube project transform conventions", () => {
  it("keeps GeckoLib project positions, rotations, and bounds in their saved axes", () => {
    expect(GECKOLIB_BBMODEL_TRANSFORMS.position([1, 2, 3], (value) => -value)).toEqual([1, 2, 3]);
    expect(GECKOLIB_BBMODEL_TRANSFORMS.rotation([10, 20, 30], (value) => -value)).toEqual([10, 20, 30]);
    expect(GECKOLIB_BBMODEL_TRANSFORMS.bounds([1, 2, 3], [4, 5, 6])).toEqual({ from: [1, 2, 3], to: [4, 5, 6] });
  });
});
