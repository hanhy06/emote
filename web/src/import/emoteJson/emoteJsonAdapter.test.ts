import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { createDefaultPlayerBehavior, type Matrix16, type Schema3EmoteAnimation } from "../../format/emoteAnimation";
import { emoteJsonAdapter } from "./emoteJsonAdapter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const encoder = new TextEncoder();

describe("emoteJsonAdapter", () => {
  it("reimports converted JSON without losing skin order or interpolation duration", async () => {
    const source: Schema3EmoteAnimation = {
      type: "animation",
      schema_version: 3,
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
          space: "partner",
          item_stack_snbt: "{id:\"minecraft:player_head\",count:1}",
          item_display: "none",
          default_matrix: IDENTITY,
          skin: { participant: "partner", part: "right_arm", order: 1 },
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

    expect(project.nodes.arm.type === "item_display" && project.nodes.arm.skin).toEqual({ participant: "partner", part: "right_arm", order: 1 });
    expect(project.nodes.arm.space).toBe("partner");
    expect(recompiled.settings.player).toEqual(source.settings.player);
    expect(recompiled.id).toBe(source.id);
    expect(recompiled.settings.playback.mode).toBe("server_sync");
    expect(recompiled.timeline.tracks.arm.position?.map((frame) => frame.time)).toEqual(["0t", "2t", "4t"]);
    expect(recompiled.timeline.tracks.arm.position?.[1].interpolation).toBe("linear");
  });

  it("reports the path of malformed runtime input", async () => {
    const malformed = {
      type: "animation",
      schema_version: 3,
      id: "demo:broken",
      metadata: null,
    };

    await expect(emoteJsonAdapter.import({ name: "broken.json", bytes: encoder.encode(JSON.stringify(malformed)) }))
      .rejects.toThrow("metadata must be an object");
  });

  it("does not accept unreleased schema 2 animations", async () => {
    const animation = {
      type: "animation",
      schema_version: 2,
      id: "demo:unreleased",
      metadata: { name: "Unreleased", description: "" },
      settings: {
        standalone: true,
        cooldown: "0t",
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once", loop_delay: "0t" },
      },
      nodes: {},
      timeline: { duration: "1t", keyframes: [] },
    };
    const input = {
      name: "emote.unreleased.json",
      bytes: encoder.encode(JSON.stringify(animation)),
    };

    expect((await emoteJsonAdapter.probe(input)).confidence).toBe(0);
    await expect(emoteJsonAdapter.import(input)).rejects.toThrow("schema_version: must be 3");
  });

  it("automatically migrates schema 1 animations", async () => {
    const schema1 = {
      schema_version: 1,
      minecraft_version: "26.2",
      tick_rate: 20,
      id: "legacy:wave",
      metadata: { name: "Legacy Wave", description: "Schema 1 emote." },
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
        loop: "loop",
        loop_delay_ticks: 2,
        keyframes: [
          { tick: 0, node_transforms: { arm: { matrix: IDENTITY } } },
          {
            tick: 4,
            interpolation_duration_ticks: 3,
            node_transforms: { arm: { matrix: IDENTITY, interpolation_duration_ticks: 2 } },
          },
        ],
        events: {
          timeline: [{
            tick: 2,
            source: { type: "server" },
            origin: { type: "root" },
            commands: ["say legacy"],
          }],
        },
      },
    };
    const input = { name: "emote.legacy_wave.json", bytes: encoder.encode(JSON.stringify(schema1)) };

    expect((await emoteJsonAdapter.probe(input)).reason).toBe("matches emote animation schema 1");
    const project = await emoteJsonAdapter.import(input);
    const [recompiled] = compileImportedProject(project, {
      minecraftVersion: project.suggestedMinecraftVersion ?? "unexpected",
      namespace: project.suggestedNamespace,
    });

    expect(project.suggestedMinecraftVersion).toBe("26.2");
    expect(project.suggestedStandalone).toBe(true);
    expect(project.suggestedCooldown).toBe("0t");
    expect(project.animations[0].events.timeline[0].tick).toBe(2);
    expect(recompiled.type).toBe("animation");
    expect(recompiled.schema_version).toBe(4);
    expect(recompiled.settings.playback).toEqual({ mode: "loop", loop_delay: "2t" });
    expect(recompiled.timeline.duration).toBe("4t");
    expect(recompiled.timeline.tracks.arm.position?.map((frame) => frame.time)).toEqual(["0t", "2t", "4t"]);
    expect(recompiled.timeline.events?.timeline?.[0].time).toBe("2t");
  });
});
