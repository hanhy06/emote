import { describe, expect, it } from "vitest";
import type { Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../import/types";
import { compileImportedProject } from "./animationCompiler";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("compileImportedProject time handling", () => {
  it("writes interpolation duration on the current target keyframe", () => {
    const project: ImportedProject = {
      source: "emote_json",
      sourceName: "test.json",
      suggestedMetadata: { name: "Test", description: "Test emote.", command_name: "test", hide_player: true },
      nodes: { anchor: { id: "anchor", parentId: null, type: "anchor", defaultMatrix: IDENTITY } },
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
          },
        },
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      artifacts: [],
    };

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });
    const durations = animation.timeline.keyframes.map((keyframe) => keyframe.node_transforms?.anchor.interpolation_duration_ticks);

    expect(durations).toEqual([0, 2, 3]);
  });
});
