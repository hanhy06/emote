import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type Matrix16 } from "../format/emoteAnimation";
import type { ImportedProject } from "../import/types";
import { compileImportedAnimation, compileImportedProject } from "./animationCompiler";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

describe("compileImportedProject time handling", () => {
  it("writes interpolation duration on the current target keyframe", () => {
    const project = importedProject();

    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" });
    const durations = animation.timeline.keyframes.map((keyframe) => keyframe.node_transforms?.anchor.interpolation_duration_ticks);

    expect(durations).toEqual([0, 2, 3]);
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

    expect(animation.nodes.anchor.default_matrix).toEqual(initialPose);
  });

  it("compiles only the selected animation after validating project identifiers", () => {
    const project = importedProject();
    project.animations.push({ ...project.animations[0], id: "broken", durationTicks: -1 });

    expect(compileImportedAnimation(project, { minecraftVersion: "26.2", namespace: "test" }, 0).timeline.duration_ticks).toBe(10);
  });

  it("rejects ids that collide after resource path normalization", () => {
    const project = importedProject();
    project.animations.push({ ...project.animations[0], id: "Test" });

    expect(() => compileImportedProject(project, { minecraftVersion: "26.2", namespace: "test" })).toThrow("normalize to the same id");
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
          },
        },
        events: { start: [], timeline: [], loop: [], stop: [] },
      }],
      diagnostics: [],
      resources: new Map(),
  };
}
