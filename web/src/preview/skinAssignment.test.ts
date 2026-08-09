import { describe, expect, it } from "vitest";
import { isPlayerHeadItemStack, selectPart, selectParts } from "./skinAssignment";

describe("isPlayerHeadItemStack", () => {
  it("recognizes quoted and bare player heads with the default namespace", () => {
    expect(isPlayerHeadItemStack('{id:"minecraft:player_head",count:1}')).toBe(true);
    expect(isPlayerHeadItemStack('{id:"player_head",count:1}')).toBe(true);
    expect(isPlayerHeadItemStack("{id:player_head,count:1}")).toBe(true);
  });

  it("does not treat other or malformed item ids as player heads", () => {
    expect(isPlayerHeadItemStack("{id:stone,count:1}")).toBe(false);
    expect(isPlayerHeadItemStack("{id:{nested:player_head},count:1}")).toBe(false);
  });
});

describe("selectPart", () => {
  it("selects only the clicked model without additive selection", () => {
    expect([...selectPart(new Set(["part_10"]), "part_7", false)]).toEqual(["part_7"]);
  });

  it("toggles only the clicked model during additive selection", () => {
    expect([...selectPart(new Set(["part_7"]), "part_10", true)]).toEqual(["part_7", "part_10"]);
    expect([...selectPart(new Set(["part_7", "part_10"]), "part_10", true)]).toEqual(["part_7"]);
  });

  it("adds every model inside a selection range", () => {
    expect([...selectParts(new Set(["part_2"]), ["part_7", "part_10"], true)])
      .toEqual(["part_2", "part_7", "part_10"]);
  });
});
