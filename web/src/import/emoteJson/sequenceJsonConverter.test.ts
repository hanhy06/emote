import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { convertSequenceInput } from "./sequenceJsonConverter";

const encoder = new TextEncoder();

describe("convertSequenceInput", () => {
  it("migrates schema 1 sequences to schema 4", () => {
    const input = {
      name: "emote.inventory.json",
      bytes: encoder.encode(JSON.stringify({
        type: "sequence",
        schema_version: 1,
        id: "emote:inventory",
        metadata: { name: "Inventory", description: "" },
        player: createDefaultPlayerBehavior(),
        steps: [
          { emote: "project:inventory_opening" },
          { emote: "project:default", repeat: 3 },
          { emote: "project:inventory_closing" },
        ],
      })),
    };

    expect(convertSequenceInput(input)).toEqual({
      type: "sequence",
      schema_version: 4,
      id: "emote:inventory",
      metadata: { name: "Inventory", description: "" },
      settings: { cooldown: "0t", player: createDefaultPlayerBehavior() },
      steps: [
        { emote: "project:inventory_opening" },
        { emote: "project:default", repeat: 3 },
        { emote: "project:inventory_closing" },
      ],
    });
  });

  it("rejects schema 3 sequences", () => {
    const sequence = {
      type: "sequence",
      schema_version: 3,
      id: "emote:sit",
      metadata: { name: "Sit", description: "" },
      settings: { cooldown: "5s", player: createDefaultPlayerBehavior() },
      steps: [{ emote: "emote:sit_down" }, { wait: "10t" }, { emote: ["emote:idle", 100], repeat: 3 }],
    };

    expect(() => convertSequenceInput({ name: "emote.sit.json", bytes: encoder.encode(JSON.stringify(sequence)) }))
      .toThrow("Unsupported sequence schema: 3");
  });

  it("preserves transition times on schema 4 emote steps", () => {
    const sequence = {
      type: "sequence",
      schema_version: 4,
      id: "emote:sit",
      metadata: { name: "Sit", description: "" },
      settings: { cooldown: "5s", player: createDefaultPlayerBehavior() },
      steps: [{ emote: "emote:sit_down" }, { emote: "emote:sit_idle", transition: "4t" }],
    };

    expect(convertSequenceInput({ name: "emote.sit.json", bytes: encoder.encode(JSON.stringify(sequence)) })?.steps)
      .toEqual([{ emote: "emote:sit_down" }, { emote: "emote:sit_idle", transition: "4t" }]);
  });

  it("ignores non-sequence JSON", () => {
    expect(convertSequenceInput({ name: "animation.json", bytes: encoder.encode('{"type":"animation"}') })).toBeNull();
  });

  it("ignores non-JSON inputs so other adapters can inspect them", () => {
    expect(convertSequenceInput({ name: "project.zip", bytes: new Uint8Array([80, 75, 3, 4]) })).toBeNull();
  });
});
