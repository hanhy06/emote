import { describe, expect, it } from "vitest";
import { strFromU8, unzipSync } from "fflate";
import type { Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../import/types";
import { exportAnimation, exportResourcePack } from "./projectExporter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("exportAnimation", () => {
  it("writes a manually assigned order without replacing it with zero", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "test.json",
      suggestedMetadata: { name: "Test", description: "Test emote.", hide_player: true },
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
      artifacts: new Map(),
    };
    const result = exportAnimation(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "server_sync",
      ...project.suggestedMetadata,
    }, { body: { part: "body", order: 9 } }, 0);
    const animation = JSON.parse(await result.blob.text());

    expect(animation.nodes.body.skin).toEqual({ part: "body", order: 9 });
    expect(animation.metadata).toEqual({ name: "Test", description: "Test emote.", hide_player: true });
    expect(animation.schema_version).toBe(1);
    expect(animation.timeline.loop).toBe("server_sync");
    expect(result.fileName).toBe("emote.test.json");
  });

  it("packages generated resources under the animation export name", async () => {
    const texture = new Uint8Array([1, 2, 3]);
    const project: ImportedProject = {
      source: "animated_java_json",
      sourceName: "test.ajblueprint",
      suggestedMetadata: { name: "Test Emote", description: "Test emote.", hide_player: false },
      suggestedMinecraftVersion: "26.2",
      artifactMinecraftVersion: "26.2",
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
      artifacts: new Map([["assets/test/textures/entity/test.png", texture]]),
    };
    const result = exportResourcePack(project, {
      minecraftVersion: "26.2",
      namespace: "test",
      playbackMode: "source",
      ...project.suggestedMetadata,
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
});
