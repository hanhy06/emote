import { describe, expect, it } from "vitest";
import { applySkinMarkers, injectProfileName } from "./markerWriter";

describe("injectProfileName", () => {
  it("replaces only the top-level profile name", () => {
    const item = '{id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{"minecraft:profile":{name:"old",properties:[{name:"textures",value:"abc"}]}}},Tags:["demo_0"]}';

    const updated = injectProfileName(item, "emote:head");

    expect(updated).toContain('{name:"emote:head",properties:[{name:"textures",value:"abc"}]}');
  });
});

describe("applySkinMarkers limb order", () => {
  it("writes an explicit limb order after the skin part marker", () => {
    const original = 'summon item_display ~ ~ ~ {id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{"minecraft:profile":{properties:[]}}},Tags:["demo_3"]}';

    const updated = applySkinMarkers(original, "demo", { 3: "right_arm" }, { 3: 1 });

    expect(updated).toContain('name:"emote:right_arm1"');
    expect("emote:right_arm1").toHaveLength(16);
  });
});

describe("applySkinMarkers", () => {
  it("writes markers only to explicitly assigned player heads", () => {
    const line = (index: number) => `summon item_display ~ ~ ~ {id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{}},Tags:["demo_${index}"]}`;
    const original = `${line(0)}\n${line(1)}`;

    const updated = applySkinMarkers(original, "demo", { 0: "head", 1: null });

    expect(updated.match(/name:"emote:head"/g)).toHaveLength(1);
    expect(updated).toContain(line(1));
  });

  it("removes an existing emote marker when a part becomes unassigned", () => {
    const original = 'summon item_display ~ ~ ~ {id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{"minecraft:profile":{name:"emote:head",properties:[]}}},Tags:["demo_0"]}';

    const updated = applySkinMarkers(original, "demo", { 0: null });

    expect(updated).not.toContain('name:"emote:head"');
    expect(updated).toContain('"minecraft:profile":{properties:[]}');
  });
});
