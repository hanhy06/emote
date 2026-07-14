import { describe, expect, it } from "vitest";
import { createPlayerHeadGeometry } from "./playerHeadGeometry";

describe("createPlayerHeadGeometry", () => {
  it("uses the BD Engine logical center with Minecraft head size", () => {
    const geometry = createPlayerHeadGeometry();
    geometry.computeBoundingBox();

    expect(geometry.boundingBox?.min.toArray()).toEqual([-0.25, 0.25, -0.25]);
    expect(geometry.boundingBox?.max.toArray()).toEqual([0.25, 0.75, 0.25]);

    geometry.dispose();
  });
});
