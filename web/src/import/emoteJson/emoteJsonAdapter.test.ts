import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { createDefaultPlayerBehavior, type EmoteAnimation, type Matrix16 } from "../../format/emoteAnimation";
import { serializeEmoteAnimation } from "../../format/serializer";
import { createConversionDocument } from "../../domain/conversionDocument";
import { assignDocumentNodeSpace } from "../../domain/conversionEditor";
import { compileConversionAnimation } from "../../compiler/animationCompiler";
import type { Schema3EmoteAnimation } from "./animationSchema3/animationSchema3";
import { emoteJsonAdapter } from "./emoteJsonAdapter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const encoder = new TextEncoder();

describe("emoteJsonAdapter", () => {
  it("imports schema 4 animations as the canonical input format", async () => {
    const source: EmoteAnimation = {
      type: "animation",
      schema_version: 4,
      id: "demo:wave",
      metadata: { name: "Wave", description: "Wave emote." },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 35,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "once", loop_delay: "0t" },
      },
      nodes: {
        arm: {
          type: "item_display",
          space: "initiator",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_stack_snbt: "{id:\"minecraft:stone\",count:1}",
          item_display: "none",
        },
      },
      timeline: {
        duration: "4t",
        tracks: {
          arm: {
            position: [
              { time: "0t", value: [0, 0, 0], interpolation: "linear" },
              { time: "4t", value: [1, 0, 0] },
            ],
            rotation: [
              { time: "0t", value: [0, 0, 0], interpolation: "linear" },
              { time: "4t", value: [0, 90, 0] },
            ],
            scale: [
              { time: "0t", value: [1, 1, 1], interpolation: "linear" },
              { time: "4t", value: [1, 1, 1] },
            ],
          },
        },
      },
    };
    const input = { name: "emote.wave.json", bytes: encoder.encode(JSON.stringify(source)) };

    expect((await emoteJsonAdapter.probe(input)).reason).toBe("matches emote animation schema 4");
    const project = await emoteJsonAdapter.import(input);
    const [recompiled] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "demo" });

    expect(recompiled.schema_version).toBe(4);
    expect(recompiled.settings.rotation_deadzone).toBe(35);
    expect(recompiled.timeline.tracks.arm.position?.map((frame) => frame.time)).toEqual(["0t", "4t"]);
    expect(recompiled.timeline.tracks.arm.position?.[1].value?.[0]).toBeCloseTo(1);
    expect(recompiled.timeline.tracks.arm.rotation?.[1].value?.[1]).toBeCloseTo(90);
  });

  it("preserves advanced schema 4 runtime data with a Create pose preview", async () => {
    const source: EmoteAnimation = {
      type: "animation",
      schema_version: 4,
      id: "demo:dynamic",
      metadata: { name: "Dynamic", description: "Preserve this description.", category: "demo" },
      settings: {
        standalone: true,
        cooldown: "0t",
        rotation_deadzone: 50,
        player: createDefaultPlayerBehavior(),
        playback: { mode: "loop", loop_delay: "2t" },
      },
      molang: { initialize: "v.offset = 1;" },
      nodes: {
        root: {
          type: "anchor",
          space: "initiator",
          transform: { position: [1, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
        },
        child: {
          type: "item_display",
          parent: "root",
          transform: { position: [0, 2, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          item_stack_snbt: "{id:\"minecraft:stone\",count:1}",
          item_display: "none",
        },
      },
      timeline: {
        duration: "10t",
        tracks: {
          child: {
            position: [
              { time: "0t", value: ["q.anim_time + v.offset", 0, 0], interpolation: "linear", easing: "ease_in_sine" },
              { time: "10t", value: [1, 0, 0] },
            ],
            rotation: [
              { time: "0t", pre: [0, 0, 0], post: [0, 10, 0], interpolation: "linear" },
              { time: "5t", value: [0, 90, 0] },
            ],
            visible: [{ time: "0t", value: "q.is_moving" }],
          },
        },
      },
    };
    const input = { name: "emote.dynamic.json", bytes: encoder.encode(JSON.stringify(source)) };

    const project = await emoteJsonAdapter.import(input);
    expect(project.animations[0].availability).toMatchObject({ preview: "create_pose", exportable: true });
    expect(project.diagnostics).toContainEqual(expect.objectContaining({ code: "schema_4_preview_limited" }));
    expect(project.nodes.child.defaultMatrix[3]).toBeCloseTo(1);
    expect(project.nodes.child.defaultMatrix[7]).toBeCloseTo(2);

    const document = assignDocumentNodeSpace(createConversionDocument(project, "Emote animation JSON"), new Set(["child"]), "partner");
    expect(document.nodes.root.space).toBe("partner");
    expect(document.nodes.child.space).toBe("partner");
    const recompiled = compileConversionAnimation(document, 0);
    const serialized = JSON.parse(serializeEmoteAnimation(recompiled));

    expect(serialized.nodes.root.space).toBe("partner");
    expect(serialized.nodes.child.parent).toBe("root");
    expect(serialized.metadata).toEqual(source.metadata);
    expect(serialized.molang).toEqual(source.molang);
    expect(serialized.timeline.tracks).toEqual(source.timeline.tracks);
  });

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
    await expect(emoteJsonAdapter.import(input)).rejects.toThrow("schema_version must be 4");
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
