import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./emoteAnimation";
import { requireEmoteAnimation } from "./emoteAnimationRuntime";

describe("requireEmoteAnimation", () => {
  it("rejects an item display without an item stack", () => {
    expect(() => requireEmoteAnimation({
      type: "animation",
      schema_version: 4,
      id: "demo:empty_item",
      metadata: { name: "Empty item", description: "" },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 50,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once" },
      },
      nodes: {
        item: {
          type: "item_display",
          space: "scene",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_display: "none",
        },
      },
      timeline: { duration: "1t", tracks: {} },
    })).toThrow("nodes.item.item_stack_snbt must be a string");
  });

  it.each([undefined, null])("defaults an omitted skin participant to initiator", (participant) => {
    const skin = { part: "head", order: 0, ...(participant === undefined ? {} : { participant }) };
    const animation = requireEmoteAnimation({
      type: "animation",
      schema_version: 4,
      id: "demo:wave",
      metadata: { name: "Wave", description: "" },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 50,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once" },
      },
      nodes: {
        head: {
          type: "item_display",
          space: "initiator",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_stack_snbt: "{id:\"minecraft:player_head\",count:1}",
          item_display: "none",
          skin,
        },
      },
      timeline: { duration: "1t", tracks: {} },
    });

    expect(animation.nodes.head.type === "item_display" && animation.nodes.head.skin?.participant).toBe("initiator");
    expect(animation.settings.playback).toEqual({ mode: "once" });
  });

  it("preserves an explicit partner participant", () => {
    const animation = requireEmoteAnimation({
      type: "animation",
      schema_version: 4,
      id: "demo:wave",
      metadata: { name: "Wave", description: "" },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 50,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once", loop_delay: "0t" },
      },
      nodes: {
        head: {
          type: "item_display",
          space: "partner",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_stack_snbt: "{id:\"minecraft:player_head\",count:1}",
          item_display: "none",
          skin: { participant: "partner", part: "head", order: 0 },
        },
      },
      timeline: { duration: "1t", tracks: {} },
    });

    expect(animation.nodes.head.type === "item_display" && animation.nodes.head.skin?.participant).toBe("partner");
  });

  it("accepts a Molang NBT string expression", () => {
    const animation = requireEmoteAnimation({
      type: "animation",
      schema_version: 4,
      id: "demo:flowers",
      metadata: { name: "Flowers", description: "" },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 50,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once", loop_delay: "0t" },
      },
      nodes: {
        flower: {
          type: "item_display",
          space: "initiator",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_stack_snbt: "{id:\"minecraft:poppy\",count:1}",
          item_display: "none",
        },
      },
      timeline: {
        duration: "1t",
        tracks: {
          flower: {
            nbt: [{
              time: "0t",
              value: {
                molang: "q.is_sneaking ? '{Glowing:1b}' : '{Glowing:0b}'",
              },
            }],
          },
        },
      },
    });

    expect(animation.timeline.tracks.flower.nbt?.[0].value).toMatchObject({
      molang: "q.is_sneaking ? '{Glowing:1b}' : '{Glowing:0b}'",
    });
  });
});
