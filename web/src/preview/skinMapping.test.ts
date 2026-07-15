import { describe, expect, it } from "vitest";
import { selectPart } from "./skinMapping";

describe("selectPart", () => {
  it("selects only the clicked model without additive selection", () => {
    expect([...selectPart(new Set([10]), 7, false)]).toEqual([7]);
  });

  it("toggles only the clicked model during additive selection", () => {
    expect([...selectPart(new Set([7]), 10, true)]).toEqual([7, 10]);
    expect([...selectPart(new Set([7, 10]), 10, true)]).toEqual([7]);
  });
});
