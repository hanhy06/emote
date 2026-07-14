import { describe, expect, it } from "vitest";
import { createPlayerHeadGeometry } from "./playerHeadGeometry";

describe("createPlayerHeadGeometry", () => {
  it("uses the Minecraft skull model bounds expected by BD Engine matrices", () => {
    const geometry = createPlayerHeadGeometry();
    geometry.computeBoundingBox();

    expect(geometry.boundingBox?.min.toArray()).toEqual([-0.25, -0.5, -0.25]);
    expect(geometry.boundingBox?.max.toArray()).toEqual([0.25, 0, 0.25]);

    geometry.dispose();
  });
});
