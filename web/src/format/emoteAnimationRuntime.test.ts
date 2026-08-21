import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./emoteAnimation";
import { requireEmoteAnimation } from "./emoteAnimationRuntime";

describe("requireEmoteAnimation", () => {
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
        playback: { mode: "once", loop_delay: "0t" },
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
});
