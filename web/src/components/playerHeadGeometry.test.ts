import { describe, expect, it } from "vitest";
import { createPlayerHeadGeometry } from "./playerHeadGeometry";

describe("createPlayerHeadGeometry", () => {
  it("matches the transformed Minecraft player head item bounds", () => {
    const geometry = createPlayerHeadGeometry();
    geometry.computeBoundingBox();

    expect(geometry.boundingBox?.min.toArray()).toEqual([-0.75, 0, -0.75]);
    expect(geometry.boundingBox?.max.toArray()).toEqual([-0.25, 0.5, -0.25]);

    geometry.dispose();
  });
});
