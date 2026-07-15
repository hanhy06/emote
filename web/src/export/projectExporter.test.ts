import { describe, expect, it } from "vitest";
import type { Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../import/types";
import { exportAnimation } from "./projectExporter";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("exportAnimation", () => {
  it("writes a manually assigned order without replacing it with zero", async () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "test.json",
      suggestedMetadata: { name: "Test", description: "Test emote.", command_name: "test", hide_player: true },
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
      ...project.suggestedMetadata,
    }, { body: { part: "body", order: 9 } }, 0);
    const animation = JSON.parse(await result.blob.text());

    expect(animation.nodes.body.skin).toEqual({ part: "body", order: 9 });
  });
});
