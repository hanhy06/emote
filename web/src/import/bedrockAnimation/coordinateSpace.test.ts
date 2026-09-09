import { describe, expect, it } from "vitest";
import { bedrockBoundsToCanonical, bedrockPositionToCanonical, bedrockRotationToCanonical } from "./coordinateSpace";

describe("import coordinate spaces", () => {
  it("maps Bedrock positions and rotations into the canonical emote axes", () => {
    expect(bedrockPositionToCanonical([2, -3, 4], (value) => -value)).toEqual([-2, -3, 4]);
    expect(bedrockRotationToCanonical([10, 20, 30], (value) => -value)).toEqual([-10, -20, 30]);
  });

  it("reflects Bedrock bounds while retaining ascending corners", () => {
    expect(bedrockBoundsToCanonical([-1, 2, 3], [5, 6, 7])).toEqual({
      from: [-5, 2, 3],
      to: [1, 6, 7],
    });
  });
});
