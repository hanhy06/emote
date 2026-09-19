import { readItemStack, readDisplayNbt } from "../format/minecraftData";
import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type EmoteAnimation, type Matrix16 } from "../format/emoteAnimation";
import { localTransformToMatrix } from "../format/localTransform";
import { serializeEmoteAnimation } from "../format/serializer";
import type { ImportedProject } from "../domain/conversionSeed";
import { assignDocumentNodeSpace, createConversionDocument } from "../domain/conversionDocument";
import { compileImportedAnimation, compileImportedProject } from "../test/compileImportedFixture";
import { compileConversionAnimation } from "./animationCompiler";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("compileImportedProject time handling", () => {
  it("normalizes JSON-facing settings to Minecraft ticks", () => {
    const [animation] = compileImportedProject(importedProject(), {
      minecraftVersion: "26.2",
      namespace: "test",
      standalone: false,
      cooldown: "10s",
      playbackMode: "loop",
      loopStart: "0.25s",
      loopEnd: "0.3s",
      loopDelay: "0.5s",
    });

    expect(animation.settings.standalone).toBe(false);
    expect(animation.settings.cooldown).toBe("200t");
    expect(animation.settings.rotation_deadzone).toBe(50);
    expect(animation.settings.playback).toEqual({ mode: "loop", loop_start: "5t", loop_end: "6t", loop_delay: "10t" });
  });

  it("omits zero-valued loop settings", () => {
    const [animation] = compileImportedProject(importedProject(), {
      minecraftVersion: "26.2", namespace: "test", playbackMode: "loop", loopStart: "0t", loopDelay: "0t",
    });

    expect(animation.settings.playback).toEqual({ mode: "loop" });
  });

  it("translates target durations into schema 4 outgoing interpolation", () => {
    const project = importedProject();

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });
    const frames = animation.timeline.tracks.anchor.position!;

    expect(frames.map((frame) => frame.time)).toEqual(["0t", "2t", "3t", "5t", "8t"]);
    expect(frames.map((frame) => frame.interpolation)).toEqual(["step", "step", "linear", "linear", undefined]);
  });

  it("uses the animation's tick-zero pose as the node default", () => {
    const project = importedProject();
    const initialPose: Matrix16 = [1, 0, 0, 4, 0, 1, 0, 5, 0, 0, 1, 6, 0, 0, 0, 1];
    const runtime = project.animations[0].runtime;
    if (runtime.kind !== "baked") throw new Error("Expected baked test runtime.");
    runtime.tracks.anchor.transforms.unshift({
      tick: 0,
      matrix: initialPose,
      interpolation: { type: "step" },
    });

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });

    expect(animation.nodes.anchor.transform).toEqual({ position: [4, 5, 6], rotation: [0, 0, 0], scale: [1, 1, 1] });
  });

  it("restores full turns from consecutive matrix samples", () => {
    const project = importedProject();
    const rotations = [0, 90, 180, 270, 360];
    project.animations[0].durationTicks = rotations.length - 1;
    const runtime = project.animations[0].runtime;
    if (runtime.kind !== "baked") throw new Error("Expected baked test runtime.");
    runtime.tracks.anchor.transforms = rotations.map((rotation, tick) => ({
      tick,
      matrix: localTransformToMatrix({ position: [0, 0, 0], rotation: [rotation, 0, 0], scale: [1, 1, 1] }, `${rotation} degree source`),
      interpolation: tick === 0 ? { type: "step" } : { type: "linear", durationTicks: 1 },
    }));

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });
    const output = JSON.parse(serializeEmoteAnimation(animation)) as EmoteAnimation;

    animation.timeline.tracks.anchor.rotation?.forEach((frame, index) => expect(frame.value?.[0]).toBeCloseTo(rotations[index], 10));
    expect(output.timeline.tracks.anchor.rotation?.map((frame) => frame.value?.[0])).toEqual(rotations);
  });

  it("compiles only the selected animation after validating project identifiers", () => {
    const project = importedProject();
    project.animations.push({ ...project.animations[0], id: "broken", durationTicks: -1 });

    expect(compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "test" }, 0).timeline.duration).toBe("10t");
  });

  it("rejects ids that collide after resource path normalization", () => {
    const project = importedProject();
    project.animations.push({ ...project.animations[0], id: "Test" });

    expect(() => compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" })).toThrow("normalize to the same id");
  });

  it("rejects export from the export contract independently of preview availability", () => {
    const project = importedProject();
    project.animations[0].exportAvailability = {
      exportable: false,
      reason: "Runtime Molang cannot be evaluated.",
    };

    expect(() => compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "test" }, 0))
      .toThrow("Runtime Molang cannot be evaluated.");
  });

  it("keeps runtime Molang output separate from numeric preview tracks", () => {
    const project = importedProject();
    const runtime = project.animations[0].runtime;
    if (runtime.kind !== "baked") throw new Error("Expected baked test runtime.");
    project.animations[0].preview = { durationTicks: 20, tracks: runtime.tracks, availability: { preview: "full" } };
    project.animations[0].durationTicks = 12_000;
    project.animations[0].runtime = {
      kind: "native",
      nodes: { anchor: { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } } },
      tracks: { anchor: { position: [{ time: "0t", value: ["q.anim_time", 0, 0] }] } },
      bindings: { editorNodeByRuntimeNode: {}, spaceGroupByRuntimeRoot: { anchor: "anchor" } },
    };
    project.animations[0].preview.tracks.anchor.transforms[0].matrix = [1, 0, 0, 999, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

    const animation = compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "runtime" }, 0);

    expect(animation.timeline.duration).toBe("12000t");
    expect(animation.timeline.tracks.anchor.position?.[0].value?.[0]).toBe("q.anim_time");
  });

  it("applies editor space changes through explicit native runtime bindings", () => {
    const project = importedProject();
    project.nodes = {
      display: {
        id: "display",
        type: "item_display",
        defaultMatrix: IDENTITY,
        visible: true,
        itemStack: { id: "minecraft:stone" },
        itemDisplay: "none",
        spaceAssignmentGroup: "rig",
      },
    };
    project.animations[0].preview.tracks = {};
    project.animations[0].runtime = {
      kind: "native",
      nodes: {
        runtime_root: { type: "anchor", space: "initiator", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } },
        runtime_display: {
          type: "item_display",
          parent: "runtime_root",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          itemStack: { id: "minecraft:stone" },
          item_display: "none",
        },
      },
      tracks: {},
      bindings: {
        editorNodeByRuntimeNode: { runtime_display: "display" },
        spaceGroupByRuntimeRoot: { runtime_root: "rig" },
      },
    };
    const document = assignDocumentNodeSpace(createConversionDocument(project, "test"), new Set(["display"]), "partner");

    const animation = compileConversionAnimation(document, 0);

    expect(animation.nodes.runtime_root.space).toBe("partner");
  });

  it("does not let item NBT replace an assigned player-head skin", () => {
    const project = importedProject();
    project.nodes = {
      item: {
        id: "item",
        type: "item_display",
        defaultMatrix: IDENTITY,
        visible: true,
        itemStack: readItemStack('{id:"minecraft:paper",count:1}'),
        itemDisplay: "none",
        suggestedSkin: { part: "head", order: 0 },
        playerHeadConversion: { matrix: IDENTITY },
      },
    };
    const itemTracks = {
      item: {
        transforms: [],
        visibility: [],
        nbt: [{
          tick: 0,
          value: readDisplayNbt('{item:{id:"minecraft:paper",count:1},brightness:{block:15,sky:15}}'),
        }],
      },
    };
    project.animations[0].preview.tracks = itemTracks;
    project.animations[0].runtime = { kind: "baked", tracks: itemTracks };

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "skin" });

    expect(animation.nodes.item.type === "item_display" && animation.nodes.item.item_stack_snbt).toContain("player_head");
    expect(animation.timeline.tracks.item.nbt).toEqual([{
      time: "0t",
      value: "{brightness:{block:15,sky:15}}",
    }]);
  });

  it("uses native runtime bindings when compiling display NBT", () => {
    const project = importedProject();
    project.nodes = {
      item: {
        id: "item",
        type: "item_display",
        defaultMatrix: IDENTITY,
        visible: true,
        itemStack: readItemStack('{id:"minecraft:paper",count:1}'),
        itemDisplay: "none",
        suggestedSkin: { part: "head", order: 0 },
        playerHeadConversion: { matrix: IDENTITY },
      },
    };
    project.animations[0].preview.tracks = {};
    project.animations[0].runtime = {
      kind: "native",
      nodes: {
        runtime_item: {
          type: "item_display",
          space: "initiator",
          transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
          itemStack: { id: "minecraft:paper", count: 1 },
          item_display: "none",
        },
      },
      tracks: {
        runtime_item: { nbt: [{ time: "0t", value: readDisplayNbt('{item:{id:"minecraft:paper",count:1},brightness:{block:15,sky:15}}') }] },
      },
      bindings: { editorNodeByRuntimeNode: { runtime_item: "item" }, spaceGroupByRuntimeRoot: { runtime_item: "item" } },
    };

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "skin" });

    expect(animation.timeline.tracks.runtime_item.nbt).toEqual([{ time: "0t", value: "{brightness:{block:15,sky:15}}" }]);
  });
});

function importedProject(): ImportedProject {
  const tracks = {
    anchor: {
      transforms: [
        { tick: 2, matrix: IDENTITY, interpolation: { type: "step" as const } },
        { tick: 5, matrix: IDENTITY, interpolation: { type: "linear" as const, durationTicks: 2 } },
        { tick: 8, matrix: IDENTITY, interpolation: { type: "linear" as const } },
      ],
      visibility: [],
      nbt: [],
    },
  };
  return {
      source: "emote_json",
      sourceName: "test.json",
      suggestedMetadata: { name: "Test", description: "Test emote." },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: { anchor: { id: "anchor", type: "anchor", defaultMatrix: IDENTITY } },
      animations: [{
        id: "test",
        name: "Test",
        durationTicks: 10,
        playbackMode: "once",
        loopDelayTicks: 0,
        events: { start: [], timeline: [], loop: [], stop: [] },
        preview: { durationTicks: 10, tracks, availability: { preview: "full" } },
        exportAvailability: { exportable: true },
        runtime: { kind: "baked", tracks },
      }],
      diagnostics: [],
      resources: new Map(),
  };
}
