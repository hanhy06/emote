import { describe, expect, it } from "vitest";
import type { EmoteAnimation, Matrix16 } from "./emoteAnimation";
import { serializeEmoteAnimation } from "./serializer";
import { validateEmoteAnimation } from "./validator";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

function animation(): EmoteAnimation {
  return {
    schema_version: 1,
    minecraft_version: "26.2",
    tick_rate: 20,
    id: "emote:test",
    metadata: { name: "Test", description: "Test emote.", command_name: "test", hide_player: true },
    transform_space: { coordinate_space: "root_local", matrix_layout: "row_major", matrix_size: 16 },
    nodes: {
      display: { type: "item_display", item_stack_snbt: "{id:\"minecraft:stone\",count:1}", item_display: "none", default_matrix: IDENTITY },
      effect: { type: "anchor", default_matrix: IDENTITY },
    },
    timeline: {
      duration_ticks: 2,
      loop: "once",
      loop_delay_ticks: 0,
      keyframes: [{ tick: 0, node_transforms: { display: { matrix: IDENTITY } } }],
    },
  };
}

describe("validateEmoteAnimation", () => {
  it("accepts and serializes a structurally valid animation", () => {
    const value = animation();
    expect(validateEmoteAnimation(value)).toEqual([]);
    expect(serializeEmoteAnimation(value)).toContain('"schema_version":1');
  });

  it("rejects anchor command sources and out-of-range timeline events", () => {
    const value = animation();
    value.timeline.events = {
      timeline: [{
        tick: 2,
        source: { type: "node", node: "effect" },
        origin: { type: "root" },
        commands: ["say test"],
      }],
    };
    const paths = validateEmoteAnimation(value).map((issue) => issue.path);
    expect(paths).toContain("timeline.events.timeline[0].tick");
    expect(paths).toContain("timeline.events.timeline[0].source.node");
  });
});
