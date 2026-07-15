import { describe, expect, it } from "vitest";
import { selectPart } from "./skinMapping";

describe("selectPart", () => {
  it("selects only the clicked model without additive selection", () => {
    expect([...selectPart(new Set(["part_10"]), "part_7", false)]).toEqual(["part_7"]);
  });

  it("toggles only the clicked model during additive selection", () => {
    expect([...selectPart(new Set(["part_7"]), "part_10", true)]).toEqual(["part_7", "part_10"]);
    expect([...selectPart(new Set(["part_7", "part_10"]), "part_10", true)]).toEqual(["part_7"]);
  });
});
