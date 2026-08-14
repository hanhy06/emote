import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sequenceJsonAdapter } from "./sequenceJsonAdapter";

const encoder = new TextEncoder();

describe("sequenceJsonAdapter", () => {
  it("detects and imports a sequence through the adapter contract", async () => {
    const input = {
      name: "emote.demo.json",
      bytes: encoder.encode(JSON.stringify({
        type: "sequence",
        schema_version: 3,
        id: "demo:sequence",
        metadata: { name: "Demo", description: "" },
        settings: { cooldown: "0t", player: createDefaultPlayerBehavior() },
        steps: [{ emote: "demo:first" }],
      })),
    };

    expect(sequenceJsonAdapter.probe(input)).toMatchObject({ confidence: 100 });
    await expect(sequenceJsonAdapter.import(input)).resolves.toMatchObject({
      kind: "sequence",
      fileName: "emote.demo.json",
      sequence: { id: "demo:sequence" },
    });
  });
});
