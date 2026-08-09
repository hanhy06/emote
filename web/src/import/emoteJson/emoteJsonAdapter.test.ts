import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { createDefaultPlayerBehavior, type EmoteAnimation, type Matrix16 } from "../../format/emoteAnimation";
import { emoteJsonAdapter } from "./emoteJsonAdapter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const encoder = new TextEncoder();

describe("emoteJsonAdapter", () => {
  it("reimports converted JSON without losing skin order or interpolation duration", async () => {
    const source: EmoteAnimation = {
      schema_version: 1,
      minecraft_version: "26.2",
      tick_rate: 20,
      id: "demo:wave",
      metadata: { name: "Wave", description: "Wave emote." },
      player: createDefaultPlayerBehavior(),
      transform_space: { coordinate_space: "root_local", matrix_layout: "row_major", matrix_size: 16 },
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
        duration_ticks: 4,
        loop: "server_sync",
        loop_delay_ticks: 2,
        keyframes: [
          { tick: 0, node_transforms: { arm: { matrix: IDENTITY } } },
          { tick: 4, node_transforms: { arm: { matrix: IDENTITY, interpolation_duration_ticks: 2 } } },
        ],
      },
    };
    const input = { name: "emote.wave.json", bytes: encoder.encode(JSON.stringify(source)) };

    expect((await emoteJsonAdapter.probe(input)).confidence).toBe(100);
    const project = await emoteJsonAdapter.import(input);
    const [recompiled] = compileImportedProject(project, {
      minecraftVersion: project.suggestedMinecraftVersion!,
      namespace: project.suggestedNamespace,
    });

    expect(project.nodes.arm.type === "item_display" && project.nodes.arm.skin).toEqual({ part: "right_arm", order: 1 });
    expect(recompiled.player).toEqual(source.player);
    expect(recompiled.id).toBe(source.id);
    expect(recompiled.timeline.loop).toBe("server_sync");
    expect(recompiled.timeline.keyframes[1].node_transforms?.arm.interpolation_duration_ticks).toBe(2);
  });

  it("reports the path of malformed runtime input", async () => {
    const malformed = {
      schema_version: 1,
      minecraft_version: "26.2",
      tick_rate: 20,
      id: "demo:broken",
      metadata: null,
    };

    await expect(emoteJsonAdapter.import({ name: "broken.json", bytes: encoder.encode(JSON.stringify(malformed)) }))
      .rejects.toThrow("metadata must be an object");
  });
});
