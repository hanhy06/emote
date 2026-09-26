import { describe, expect, it } from "vitest";
import { sequenceJsonAdapter } from "./sequenceJsonAdapter";

const stopConditions = {
  movement_distance: 0.3,
  jump: true,
  submerge: true,
  ride: true,
  damage: true,
  attack: true,
  game_mode_change: true,
};

function sequenceInput(shift?: unknown) {
  const sequence = {
    type: "sequence",
    schema_version: 4,
    id: "test:sequence",
    metadata: { name: "Test", description: "Test sequence." },
    settings: {
      cooldown: "0t",
      player: { hidden: true, stop_conditions: { ...stopConditions, ...(shift === undefined ? {} : { shift }) } },
    },
    steps: [{ emote: "test:animation" }],
  };
  return { name: "sequence.json", bytes: new TextEncoder().encode(JSON.stringify(sequence)) };
}

describe("sequence Shift stop condition", () => {
  it("defaults existing JSON to false and keeps an enabled option", async () => {
    expect((await sequenceJsonAdapter.import(sequenceInput())).player.stop_conditions.shift).toBe(false);
    expect((await sequenceJsonAdapter.import(sequenceInput(true))).player.stop_conditions.shift).toBe(true);
  });

  it("rejects a non-boolean Shift option", async () => {
    await expect(sequenceJsonAdapter.import(sequenceInput("true"))).rejects.toThrow("settings.player.stop_conditions.shift");
  });
});
