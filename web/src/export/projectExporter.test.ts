import { describe, expect, it } from "vitest";
import { strFromU8, unzipSync } from "fflate";
import { createDefaultPlayerBehavior, type Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../import/types";
import { generatedResourceFiles } from "./generatedResources";
import { exportAnimation, exportAnimationBundle } from "./projectExporter";
import { exportResourcePack } from "./resourcePackExporter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("exportAnimation", () => {
  it("exports multiple animations with a schema 3 sequence in one ZIP", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "multi.json",
      suggestedMetadata: { name: "Demo", description: "Sequence demo" },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: { root: { id: "root", type: "anchor", defaultMatrix: IDENTITY } },
      animations: ["enter", "idle"].map((id) => ({
        id, name: id, durationTicks: 2, loop: "once" as const, loopDelayTicks: 0,
        tracks: {}, events: { start: [], timeline: [], loop: [], stop: [] },
      })),
      diagnostics: [], resources: new Map(),
    };
    const result = exportAnimationBundle(project, {
      minecraftVersion: "26.2", namespace: "demo", playbackMode: "source", name: "Demo", description: "Sequence demo",
      player: project.suggestedPlayer, additionalMetadata: {}, cooldown: "1s",
    }, {}, true);

    const files = unzipSync(new Uint8Array(await result.blob.arrayBuffer()));
    const sequenceName = Object.keys(files).find((name) => name.endsWith(".sequence.json"))!;
    const sequence = JSON.parse(strFromU8(files[sequenceName]));
    expect(sequence.schema_version).toBe(3);
    expect(sequence.settings.cooldown).toBe("20t");
    expect(sequence.steps).toEqual([{ emote: "demo:enter" }, { emote: "demo:idle" }]);
    expect(Object.keys(files).filter((name) => !name.endsWith(".sequence.json"))).toHaveLength(2);
  });

  it("writes a manually assigned order without replacing it with zero", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "test.json",
      suggestedMetadata: { name: "Test", description: "Test emote." },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: {
        body: {
          id: "body",
          type: "item_display",
          defaultMatrix: IDENTITY,
          visible: true,
          itemStackSnbt: "{id:\"minecraft:player_head\",count:1}",
          itemDisplay: "none",
        },
      },
      animations: [{
        id: "test",
        name: "Test",
        durationTicks: 1,
        loop: "once",
        loopDelayTicks: 0,
        tracks: {},
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map(),
    };
    const result = exportAnimation(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "server_sync",
      name: project.suggestedMetadata.name,
      description: project.suggestedMetadata.description,
      player: project.suggestedPlayer,
      additionalMetadata: {},
    }, { body: { part: "body", order: 9 } }, 0);
    const animation = JSON.parse(await result.blob.text());

    expect(animation.nodes.body.skin).toEqual({ participant: "initiator", part: "body", order: 9 });
    expect(animation.nodes.body.space).toBe("initiator");
    expect(animation.metadata).toEqual({ name: "Test", description: "Test emote." });
    expect(animation.settings.player).toEqual(project.suggestedPlayer);
    expect(animation.schema_version).toBe(3);
    expect(animation.settings.playback.mode).toBe("server_sync");
    expect(result.fileName).toBe("emote.test.json");
  });

  it("exports participant node spaces and matching skin ownership", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "partner.json",
      suggestedMetadata: { name: "Partner", description: "Partner pose." },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: {
        body: {
          id: "body", type: "item_display", defaultMatrix: IDENTITY, visible: true,
          itemStackSnbt: "{id:\"minecraft:player_head\",count:1}", itemDisplay: "none",
        },
        origin: { id: "origin", type: "anchor", defaultMatrix: IDENTITY },
      },
      animations: [{
        id: "partner", name: "Partner", durationTicks: 1, loop: "once", loopDelayTicks: 0,
        tracks: {}, events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [], resources: new Map(),
    };
    const result = exportAnimation(project, {
      minecraftVersion: "26.2", namespace: "test", playbackMode: "source", name: "Partner", description: "Partner pose.",
      player: project.suggestedPlayer, additionalMetadata: {},
    }, { body: { participant: "partner", part: "body", order: 0 } }, 0, {
      body: "partner",
      origin: "partner",
    });
    const animation = JSON.parse(await result.blob.text());

    expect(animation.nodes.body.space).toBe("partner");
    expect(animation.nodes.body.skin.participant).toBe("partner");
    expect(animation.nodes.origin.space).toBe("partner");
  });

  it("replaces an assigned GeckoLib cube with a fitted player head", async () => {
    const conversion: Matrix16 = [
      0.5, 0, 0, 0.125,
      0, 0.5, 0, 0.25,
      0, 0, 0.5, 0.125,
      0, 0, 0, 1,
    ];
    const project: ImportedProject = {
      source: "geckolib_bbmodel",
      sourceName: "test.bbmodel",
      suggestedMetadata: { name: "Test", description: "Test emote." },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: {
        cube: {
          id: "cube",
          type: "item_display",
          defaultMatrix: IDENTITY,
          visible: true,
          itemStackSnbt: '{id:"minecraft:paper",count:1}',
          itemDisplay: "none",
          playerHeadConversion: { matrix: conversion },
        },
      },
      animations: [{
        id: "test",
        name: "Test",
        durationTicks: 1,
        loop: "once",
        loopDelayTicks: 0,
        tracks: {
          cube: {
            transforms: [{ tick: 0, matrix: IDENTITY, interpolation: { type: "step" } }],
            visibility: [],
          },
        },
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map(),
    };
    const result = exportAnimation(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "source",
      name: "Test",
      description: "Test emote.",
      player: project.suggestedPlayer,
      additionalMetadata: {},
    }, { cube: { part: "head", order: 0 } }, 0);
    const animation = JSON.parse(await result.blob.text());

    expect(animation.nodes.cube.item_stack_snbt).toContain("minecraft:player_head");
    expect(animation.nodes.cube.default_matrix).toEqual(conversion);
    expect(animation.nodes.cube.skin).toEqual({ participant: "initiator", part: "head", order: 0 });
    expect(animation.timeline.keyframes[0].node_transforms.cube.matrix).toEqual(conversion);

    const unassignedResult = exportAnimation(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "source",
      name: "Test",
      description: "Test emote.",
      player: project.suggestedPlayer,
      additionalMetadata: {},
    }, { cube: null }, 0);
    const unassignedAnimation = JSON.parse(await unassignedResult.blob.text());

    expect(unassignedAnimation.nodes.cube.item_stack_snbt).toContain("minecraft:paper");
    expect(unassignedAnimation.nodes.cube.skin).toBeUndefined();
    expect(unassignedAnimation.nodes.cube.default_matrix).toEqual(IDENTITY);
    expect(unassignedAnimation.timeline.keyframes[0].node_transforms.cube.matrix).toEqual(IDENTITY);
  });

  it("preserves unrecognized metadata in the exported animation", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "licensed.json",
      suggestedMetadata: { name: "Licensed", description: "", license: "Apache-2.0" },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: {},
      animations: [{
        id: "licensed",
        name: "Licensed",
        durationTicks: 1,
        loop: "once",
        loopDelayTicks: 0,
        tracks: {},
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map(),
    };
    const result = exportAnimation(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "source",
      name: "Licensed",
      description: "",
      player: project.suggestedPlayer,
      additionalMetadata: { license: "Apache-2.0", authors: ["Creator"] },
    }, {}, 0);

    expect(JSON.parse(await result.blob.text()).metadata).toEqual({
      license: "Apache-2.0",
      authors: ["Creator"],
      name: "Licensed",
      description: "",
    });
  });

  it("packages generated resources under the animation export name", async () => {
    const texture = new Uint8Array([1, 2, 3]);
    const project: ImportedProject = {
      source: "animated_java_json",
      sourceName: "test.ajblueprint",
      suggestedMetadata: { name: "Test Emote", description: "Test emote." },
      suggestedPlayer: createDefaultPlayerBehavior(),
      suggestedMinecraftVersion: "26.2",
      resourceMinecraftVersion: "26.2",
      nodes: {},
      animations: [{
        id: "test",
        name: "Test",
        durationTicks: 1,
        loop: "once",
        loopDelayTicks: 0,
        tracks: {},
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map([["assets/test/textures/entity/test.png", texture]]),
    };
    const result = exportResourcePack(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "source",
      name: project.suggestedMetadata.name,
      description: project.suggestedMetadata.description,
      player: project.suggestedPlayer,
      additionalMetadata: {},
    }, {}, 0);
    const files = unzipSync(new Uint8Array(await result.blob.arrayBuffer()));
    const metadata = JSON.parse(strFromU8(files["pack.mcmeta"]));

    expect(result.fileName).toBe("emote.test_emote.zip");
    expect(Object.keys(files).sort()).toEqual(["assets/test/textures/entity/test.png", "pack.mcmeta"]);
    expect(files["assets/test/textures/entity/test.png"]).toEqual(texture);
    expect(metadata).toEqual({
      pack: {
        description: "Test Emote emote resources",
        min_format: [88, 0],
        max_format: [88, 0],
      },
    });
  });

  it.each([
    "textures/entity/test.png",
    "assets/Test/textures/entity/test.png",
    "assets/test/textures/entity/Test.png",
    "assets/test/../test.png",
    "assets/test//test.png",
  ])("rejects invalid generated resource path %s", (path) => {
    const project: ImportedProject = {
      source: "animated_java_json",
      sourceName: "test.ajblueprint",
      suggestedMetadata: { name: "Test", description: "" },
      suggestedPlayer: createDefaultPlayerBehavior(),
      resourceMinecraftVersion: "26.2",
      nodes: {},
      animations: [],
      diagnostics: [],
      resources: new Map([[path, new Uint8Array([1])]]),
    };

    expect(() => generatedResourceFiles(project, "26.2"))
      .toThrow(`Generated resource has an invalid pack path: ${path}`);
  });

  it("does not allow generated resources to replace pack metadata", () => {
    const project: ImportedProject = {
      source: "animated_java_json",
      sourceName: "test.ajblueprint",
      suggestedMetadata: { name: "Test", description: "" },
      suggestedPlayer: createDefaultPlayerBehavior(),
      resourceMinecraftVersion: "26.2",
      nodes: {},
      animations: [],
      diagnostics: [],
      resources: new Map([["pack.mcmeta", new Uint8Array([1])]]),
    };

    expect(() => generatedResourceFiles(project, "26.2"))
      .toThrow("Generated resources cannot replace pack.mcmeta.");
  });
});
