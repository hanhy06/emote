import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { convertSequenceInput } from "./sequenceJsonConverter";

const encoder = new TextEncoder();

describe("convertSequenceInput", () => {
  it("migrates schema 1 sequences to schema 3", () => {
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
      schema_version: 3,
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

  it("accepts and migrates schema 2 sequences", () => {
    const sequence = {
      type: "sequence",
      schema_version: 2,
      id: "emote:sit",
      metadata: { name: "Sit", description: "" },
      settings: { cooldown: "5s", player: createDefaultPlayerBehavior() },
      steps: [{ emote: "emote:sit_down" }, { wait: "10t" }, { emote: ["emote:idle", 100], repeat: 3 }],
    };

    expect(convertSequenceInput({ name: "emote.sit.json", bytes: encoder.encode(JSON.stringify(sequence)) })).toEqual({
      ...sequence,
      schema_version: 3,
    });
  });

  it("ignores non-sequence JSON", () => {
    expect(convertSequenceInput({ name: "animation.json", bytes: encoder.encode('{"type":"animation"}') })).toBeNull();
  });

  it("ignores non-JSON inputs so other adapters can inspect them", () => {
    expect(convertSequenceInput({ name: "project.zip", bytes: new Uint8Array([80, 75, 3, 4]) })).toBeNull();
  });
});
