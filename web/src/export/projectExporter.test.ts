import { describe, expect, it } from "vitest";
import { strFromU8, unzipSync } from "fflate";
import { createDefaultPlayerBehavior, type Matrix16, type NodeSpace } from "../format/emoteAnimation";
import { createConversionDocument, type AnimationOutputSettings } from "../domain/conversionDocument";
import type { ImportedProject, ImportedSkinPart } from "../domain/conversionSeed";
import { generatedResourceFiles } from "./generatedResources";
import { exportDocumentAnimation, exportDocumentAnimationFiles } from "./projectExporter";
import { exportDocumentResourcePack } from "./resourcePackExporter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

interface FixtureExportOptions {
  minecraftVersion: string;
  namespace: string;
  playbackMode: AnimationOutputSettings["playbackMode"];
  name: string;
  description: string;
  player: AnimationOutputSettings["player"];
  additionalMetadata: Record<string, unknown>;
  standalone?: boolean;
  cooldown?: string;
  loopDelay?: string;
}

function exportAnimation(
  project: ImportedProject,
  options: FixtureExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
  spaces: Readonly<Record<string, NodeSpace>> = {},
) {
  return exportDocumentAnimation(exportDocument(project, options, skinAssignments, spaces), animationIndex);
}

function exportResourcePack(
  project: ImportedProject,
  options: FixtureExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  animationIndex: number,
  spaces: Readonly<Record<string, NodeSpace>> = {},
) {
  return exportDocumentResourcePack(exportDocument(project, options, skinAssignments, spaces), animationIndex);
}

function exportDocument(
  project: ImportedProject,
  options: FixtureExportOptions,
  skinAssignments: Readonly<Record<string, ImportedSkinPart | null>>,
  spaces: Readonly<Record<string, NodeSpace>>,
) {
  const document = createConversionDocument(project, "Test adapter");
  document.targetMinecraftVersion = options.minecraftVersion;
  document.animations[0].output = {
    namespace: options.namespace,
    playbackMode: options.playbackMode,
    displayName: options.name,
    description: options.description,
    player: options.player,
    additionalMetadata: options.additionalMetadata,
    standalone: options.standalone ?? true,
    cooldown: options.cooldown ?? "0t",
    rotationDeadzone: document.animations[0].output.rotationDeadzone,
    loopDelay: options.loopDelay ?? document.animations[0].output.loopDelay,
  };
  for (const [nodeId, space] of Object.entries(spaces)) {
    if (document.nodes[nodeId]) document.nodes[nodeId].space = space;
  }
  for (const [nodeId, skin] of Object.entries(skinAssignments)) {
    const node = document.nodes[nodeId];
    if (node?.type !== "item_display" || !node.skinGroupId) continue;
    document.skinGroups[node.skinGroupId].assignment = skin ? { part: skin.part, order: skin.order } : null;
    if (skin) node.space = skin.participant ?? (node.space === "scene" ? "initiator" : node.space);
  }
  return document;
}

describe("exportAnimation", () => {
  it("exports multiple animations and a schema 4 sequence as individual files", async () => {
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
    const document = createConversionDocument(project, "Test adapter");
    document.animations[0].output = { ...document.animations[0].output, displayName: "Entry display", cooldown: "1s" };
    document.animations[1].output = { ...document.animations[1].output, displayName: "Idle display", cooldown: "2s" };
    document.sequence.cooldown = "1s";
    const files = exportDocumentAnimationFiles(document, true);

    const sequenceFile = files.find((file) => file.fileName.endsWith(".sequence.json"))!;
    const sequenceJson = await sequenceFile.blob.text();
    const sequence = JSON.parse(sequenceJson);
    expect(sequenceJson).toContain('\n  "steps": [\n');
    expect(sequence.schema_version).toBe(4);
    expect(sequence.id).toBe("demo:demo");
    expect(sequence.metadata.name).toBe("Demo");
    expect(sequence.settings.cooldown).toBe("20t");
    expect(sequence.steps).toEqual([{ emote: "demo:enter" }, { emote: "demo:idle" }]);
    const animationFiles = files.filter((file) => !file.fileName.endsWith(".sequence.json"));
    const animationNames = animationFiles.map((file) => file.fileName);
    expect(animationNames).toEqual(["emote.1.entry_display.json", "emote.2.idle_display.json"]);
    for (const animationFile of animationFiles) {
      const animationJson = await animationFile.blob.text();
      expect(animationJson).not.toContain("\n");
      expect(JSON.parse(animationJson).settings.standalone).toBe(false);
    }
    const idleJson = await animationFiles[1].blob.text();
    expect(JSON.parse(idleJson).settings.cooldown).toBe("40t");
    expect(JSON.parse(idleJson).metadata.name).toBe("Idle display");
    expect(sequenceFile.fileName).toBe("emote.demo.sequence.json");

    const standaloneFiles = exportDocumentAnimationFiles(document, false);
    expect(standaloneFiles.map((file) => file.fileName)).toEqual([
      "emote.1.entry_display.json",
      "emote.2.idle_display.json",
    ]);
    expect(JSON.parse(await standaloneFiles[0].blob.text()).settings.standalone).toBe(true);

    const singleResult = exportDocumentAnimation(document, 0);
    expect(singleResult.fileName).toBe("emote.entry_display.json");
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
    expect(animation.schema_version).toBe(4);
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
    expect(animation.nodes.cube.transform).toEqual({ position: [0.125, 0.25, 0.125], rotation: [0, 0, 0], scale: [0.5, 0.5, 0.5] });
    expect(animation.nodes.cube.skin).toEqual({ participant: "initiator", part: "head", order: 0 });
    expect(animation.timeline.tracks.cube.position[0].value).toEqual([0.125, 0.25, 0.125]);
    expect(animation.timeline.tracks.cube.scale[0].value).toEqual([0.5, 0.5, 0.5]);

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
    expect(unassignedAnimation.nodes.cube.transform).toEqual({ position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] });
    expect(unassignedAnimation.timeline.tracks.cube.position[0].value).toEqual([0, 0, 0]);
  });

  it("preserves unrecognized metadata in the exported animation", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "licensed.json",
      suggestedMetadata: { name: "Licensed", description: "", license: "Apache-2.0" },
      suggestedPlayer: createDefaultPlayerBehavior(),
      nodes: { root: { id: "root", type: "anchor", defaultMatrix: IDENTITY } },
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
