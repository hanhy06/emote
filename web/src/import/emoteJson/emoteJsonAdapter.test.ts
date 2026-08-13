import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { createDefaultPlayerBehavior, type EmoteAnimation, type Matrix16 } from "../../format/emoteAnimation";
import { emoteJsonAdapter } from "./emoteJsonAdapter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const encoder = new TextEncoder();

describe("emoteJsonAdapter", () => {
  it("reimports converted JSON without losing skin order or interpolation duration", async () => {
    const source: EmoteAnimation = {
      type: "animation",
      schema_version: 2,
      id: "demo:wave",
      metadata: { name: "Wave", description: "Wave emote." },
      settings: {
        standalone: true,
        cooldown: "0t",
        player: createDefaultPlayerBehavior(),
        playback: { mode: "server_sync", loop_delay: "2t" },
      },
      nodes: {
        arm: {
          type: "item_display",
          item_stack_snbt: "{id:\"minecraft:player_head\",count:1}",
          item_display: "none",
          default_matrix: IDENTITY,
          skin: { part: "right_arm", order: 1 },
        },
      },
      timeline: {
        duration: "4t",
        keyframes: [
          { time: "0t", node_transforms: { arm: { matrix: IDENTITY } } },
          { time: "4t", node_transforms: { arm: { matrix: IDENTITY, interpolation_duration: "2t" } } },
        ],
      },
    };
    const input = { name: "emote.wave.json", bytes: encoder.encode(JSON.stringify(source)) };

    expect((await emoteJsonAdapter.probe(input)).confidence).toBe(100);
    const project = await emoteJsonAdapter.import(input);
    const [recompiled] = compileImportedProject(project, {
      minecraftVersion: "26.2",
      namespace: project.suggestedNamespace,
    });

    expect(project.nodes.arm.type === "item_display" && project.nodes.arm.skin).toEqual({ part: "right_arm", order: 1 });
    expect(recompiled.settings.player).toEqual(source.settings.player);
    expect(recompiled.id).toBe(source.id);
    expect(recompiled.settings.playback.mode).toBe("server_sync");
    expect(recompiled.timeline.keyframes[1].node_transforms?.arm.interpolation_duration).toBe("2t");
  });

  it("reports the path of malformed runtime input", async () => {
    const malformed = {
      type: "animation",
      schema_version: 2,
      id: "demo:broken",
      metadata: null,
    };

    await expect(emoteJsonAdapter.import({ name: "broken.json", bytes: encoder.encode(JSON.stringify(malformed)) }))
      .rejects.toThrow("metadata must be an object");
  });
});
