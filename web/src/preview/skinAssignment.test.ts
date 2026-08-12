import { describe, expect, it } from "vitest";
import { assignSkinPart, isPlayerHeadItemStack, selectPart, selectParts } from "./skinAssignment";

describe("assignSkinPart", () => {
  it("automatically appends the next order for the selected skin part", () => {
    const result = assignSkinPart(
      { body_0: "body", body_1: "body", head_0: "head", selected: null },
      { body_0: 0, body_1: 1, head_0: 4, selected: null },
      ["selected"],
      "body",
    );

    expect(result.assignments.selected).toBe("body");
    expect(result.orders.selected).toBe(2);
  });

  it("reuses the current part count after an assignment is removed", () => {
    const result = assignSkinPart(
      { body_0: "body", removed: null, selected: null },
      { body_0: 0, removed: null, selected: null },
      ["selected"],
      "body",
    );

    expect(result.orders.selected).toBe(1);
  });

  it("assigns one order to every texture variant in a logical part", () => {
    const result = assignSkinPart(
      { head: null, head_variant_1: null, body: "body" },
      { head: null, head_variant_1: null, body: 0 },
      ["head_variant_1"],
      "head",
      { head: "head", head_variant_1: "head", body: "body" },
    );

    expect(result.assignments.head).toBe("head");
    expect(result.assignments.head_variant_1).toBe("head");
    expect(result.orders.head).toBe(0);
    expect(result.orders.head_variant_1).toBe(0);
  });

  it("counts logical parts instead of their texture variants", () => {
    const result = assignSkinPart(
      { first: "head", first_variant_1: "head", selected: null },
      { first: 0, first_variant_1: 0, selected: null },
      ["selected"],
      "head",
      { first: "first", first_variant_1: "first", selected: "selected" },
    );

    expect(result.orders.selected).toBe(1);
  });

  it("does not auto-increment orders for multiple selected models", () => {
    const result = assignSkinPart(
      { existing: "left_arm", first: "head", second: null },
      { existing: 2, first: 4, second: null },
      ["first", "second"],
      "left_arm",
    );

    expect(result.orders.first).toBe(4);
    expect(result.orders.second).toBe(0);
  });

  it("removes the order when models are unassigned", () => {
    const result = assignSkinPart({ selected: "head" }, { selected: 3 }, ["selected"], null);

    expect(result.assignments.selected).toBeNull();
    expect(result.orders.selected).toBeNull();
  });
});

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
