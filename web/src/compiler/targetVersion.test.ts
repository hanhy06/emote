import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type EmoteAnimation } from "../format/emoteAnimation";
import { readBlockState, readDisplayNbt, readItemStack, writeBlockState, writeDisplayNbt } from "../format/minecraftData";
import { minecraftVersionProfile } from "../format/minecraftVersionProfiles";
import { createConversionDocument } from "../domain/conversionDocument";
import type { ImportedProject } from "../domain/conversionSeed";
import { emoteJsonAdapter } from "../import/emoteJson/emoteJsonAdapter";
import { compileConversionAnimation } from "./animationCompiler";

describe("Minecraft target output", () => {
  it.each([
    '{Name:"custom:block",Properties:{axis:"y"},custom:{Name:"keep",bytes:[B;1b,2b]}}',
    '{id:"custom:block",properties:{axis:"y"},custom:{Name:"keep",bytes:[B;1b,2b]}}',
  ])("normalizes block fields without changing unrelated NBT: %s", (source) => {
    const state = readBlockState(source);
    expect(state.id).toBe("custom:block");
    expect(state.properties).toEqual({ axis: "y" });
    expect(writeBlockState(state, minecraftVersionProfile("26.3")))
      .toBe('{id:"custom:block",properties:{axis:"y"},custom:{Name:"keep",bytes:[B;1b,2b]}}');
  });

  it("expands a compact block id and preserves a properties-only patch", () => {
    expect(writeBlockState(readBlockState('"minecraft:stone"'), minecraftVersionProfile("26.2"))).toBe('{Name:"minecraft:stone"}');
    const patch = readDisplayNbt('{block_state:{Properties:{axis:"x"}},item:{components:{"custom:value":{data:[L;1L]}}},custom:{Name:"untouched"}}');
    expect(writeDisplayNbt(patch, minecraftVersionProfile("26.3")))
      .toBe('{custom:{Name:"untouched"},block_state:{properties:{axis:"x"}},item:{components:{"custom:value":{data:[L;1L]}}}}');
    expect(patch.blockState?.id).toBeUndefined();
    expect(patch.itemStack?.id).toBeUndefined();
    expect(patch.itemStack?.count).toBeUndefined();
  });

  it("changes normal node and keyframe output without mutating the document", () => {
    const project: ImportedProject = {
      source: "emote_json", sourceName: "block.json", suggestedMetadata: { name: "Block", description: "" },
      suggestedPlayer: createDefaultPlayerBehavior(), diagnostics: [], resources: new Map(),
      nodes: {
        block: { id: "block", type: "block_display", visible: true, defaultMatrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1], blockState: readBlockState('{Name:"minecraft:oak_log",Properties:{axis:"y"}}') },
      },
      animations: [{
        id: "block", name: "Block", durationTicks: 2, loop: "once", loopDelayTicks: 0,
        events: { start: [], timeline: [], loop: [], stop: [] },
        tracks: { block: { transforms: [], visibility: [], nbt: [{ tick: 0, value: readDisplayNbt('{block_state:{Properties:{axis:"x"}}}') }] } },
      }],
    };
    const document = createConversionDocument(project, "test");
    const original = JSON.stringify(document.nodes);
    for (const version of ["26.2", "26.3", "26.2"]) {
      document.targetMinecraftVersion = version;
      const animation = compileConversionAnimation(document, 0);
      const node = animation.nodes.block;
      expect(node.type === "block_display" && node.block_state_snbt).toBe(version === "26.3"
        ? '{id:"minecraft:oak_log",properties:{axis:"y"}}' : '{Name:"minecraft:oak_log",Properties:{axis:"y"}}');
      expect(animation.timeline.tracks.block.nbt?.[0].value).toBe(version === "26.3"
        ? '{block_state:{properties:{axis:"x"}}}' : '{block_state:{Properties:{axis:"x"}}}');
      expect(JSON.stringify(document.nodes)).toBe(original);
    }
  });

  it("converts imported runtime nodes and every conditional NBT option", async () => {
    const source: EmoteAnimation = {
      type: "animation", schema_version: 4, id: "test:block", metadata: { name: "Block", description: "" },
      settings: { standalone: true, cooldown: "0t", rotation_deadzone: 50, player: createDefaultPlayerBehavior(), playback: { mode: "once", loop_delay: "0t" } },
      nodes: {
        root: { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } },
        block: { type: "block_display", parent: "root", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] }, block_state_snbt: '{Name:"minecraft:stone"}' },
      },
      timeline: { duration: "2t", tracks: { block: { nbt: [{ time: "0t", value: {
        select: "v.choice", options: ['{block_state:{Name:"minecraft:oak_log",Properties:{axis:"x"}},Glowing:0b}', '{block_state:{Properties:{axis:"z"}},Glowing:1b}'],
      } }] } } },
    };
    const project = await emoteJsonAdapter.import({ name: "block.json", bytes: new TextEncoder().encode(JSON.stringify(source)) });
    expect(project.animations[0].runtime).toBeDefined();
    const document = createConversionDocument(project, "test");
    document.targetMinecraftVersion = "26.3";
    const output = compileConversionAnimation(document, 0);
    expect(output.nodes.block).toMatchObject({ parent: "root", block_state_snbt: '{id:"minecraft:stone"}' });
    expect(output.nodes.block).not.toHaveProperty("blockState");
    expect(output.timeline.tracks.block.nbt?.[0].value).toEqual({ select: "v.choice", options: [
      '{Glowing:0b,block_state:{id:"minecraft:oak_log",properties:{axis:"x"}}}', '{Glowing:1b,block_state:{properties:{axis:"z"}}}',
    ] });
  });

  it("retains component payloads and count omission while reading items", () => {
    expect(readItemStack('{id:paper,components:{"custom:data":{numbers:[I;1,2],Name:"keep"}}}'))
      .toEqual({ id: "paper", components: [{ name: "custom:data", value: '{numbers:[I;1,2],Name:"keep"}' }] });
  });
});
