import { describe, expect, it } from "vitest";
import { selectNode, selectNodes } from "./skinParts";

describe("selectPart", () => {
  it("selects only the clicked model without additive selection", () => {
    expect([...selectNode(new Set(["part_10"]), "part_7", false)]).toEqual(["part_7"]);
  });

  it("toggles only the clicked model during additive selection", () => {
    expect([...selectNode(new Set(["part_7"]), "part_10", true)]).toEqual(["part_7", "part_10"]);
    expect([...selectNode(new Set(["part_7", "part_10"]), "part_10", true)]).toEqual(["part_7"]);
  });

  it("adds every model inside a selection range", () => {
    expect([...selectNodes(new Set(["part_2"]), ["part_7", "part_10"], true)])
      .toEqual(["part_2", "part_7", "part_10"]);
  });
});
