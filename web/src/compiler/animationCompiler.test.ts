import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../domain/conversionSeed";
import { compileImportedAnimation, compileImportedProject } from "../test/compileImportedFixture";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("compileImportedProject time handling", () => {
  it("normalizes JSON-facing settings to Minecraft ticks", () => {
    const [animation] = compileImportedProject(importedProject(), {
      minecraftVersion: "26.2",
      namespace: "test",
      standalone: false,
      cooldown: "10s",
      loop: "loop",
      loopDelay: "0.5s",
    });

    expect(animation.settings.standalone).toBe(false);
    expect(animation.settings.cooldown).toBe("200t");
    expect(animation.settings.rotation_deadzone).toBe(50);
    expect(animation.settings.playback).toEqual({ mode: "loop", loop_delay: "10t" });
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
    project.animations[0].tracks.anchor.transforms.unshift({
      tick: 0,
      matrix: initialPose,
      interpolation: { type: "step" },
    });

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });

    expect(animation.nodes.anchor.transform).toEqual({ position: [4, 5, 6], rotation: [0, 0, 0], scale: [1, 1, 1] });
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

  it("rejects export only when neither preview nor fallback output is available", () => {
    const project = importedProject();
    project.animations[0].availability = {
      preview: "unavailable",
      exportable: false,
      reason: "Runtime Molang cannot be evaluated.",
    };

    expect(() => compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "test" }, 0))
      .toThrow("Runtime Molang cannot be evaluated.");
  });

  it("keeps runtime Molang output separate from numeric preview tracks", () => {
    const project = importedProject();
    project.animations[0].preview = { durationTicks: 20, tracks: project.animations[0].tracks };
    project.animations[0].durationTicks = 12_000;
    project.animations[0].runtime = {
      nodes: { anchor: { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } } },
      timeline: {
        duration: "12000t",
        tracks: { anchor: { position: [{ time: "0t", value: ["q.anim_time", 0, 0] }] } },
      },
    };

    const animation = compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "runtime" }, 0);

    expect(animation.timeline.duration).toBe("12000t");
    expect(animation.timeline.tracks.anchor.position?.[0].value?.[0]).toBe("q.anim_time");
  });

  it("does not let item NBT replace an assigned player-head skin", () => {
    const project = importedProject();
    project.nodes = {
      item: {
        id: "item",
        type: "item_display",
        defaultMatrix: IDENTITY,
        visible: true,
        itemStackSnbt: '{id:"minecraft:paper",count:1}',
        itemDisplay: "none",
        suggestedSkin: { part: "head", order: 0 },
        playerHeadConversion: { matrix: IDENTITY },
      },
    };
    project.animations[0].tracks = {
      item: {
        transforms: [],
        visibility: [],
        nbt: [{
          tick: 0,
          value: '{item:{id:"minecraft:paper",count:1},brightness:{block:15,sky:15}}',
        }],
      },
    };

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "skin" });

    expect(animation.nodes.item.type === "item_display" && animation.nodes.item.item_stack_snbt).toContain("player_head");
    expect(animation.timeline.tracks.item.nbt).toEqual([{
      time: "0t",
      value: "{brightness:{block:15,sky:15}}",
    }]);
  });
});

function importedProject(): ImportedProject {
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
        loop: "once",
        loopDelayTicks: 0,
        tracks: {
          anchor: {
            transforms: [
              { tick: 2, matrix: IDENTITY, interpolation: { type: "step" } },
              { tick: 5, matrix: IDENTITY, interpolation: { type: "linear", durationTicks: 2 } },
              { tick: 8, matrix: IDENTITY, interpolation: { type: "linear" } },
            ],
            visibility: [],
            nbt: [],
          },
        },
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map(),
  };
}
