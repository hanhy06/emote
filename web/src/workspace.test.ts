import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./format/emoteAnimation";
import { IDENTITY_MATRIX } from "./format/matrix";
import type { ImportedProject } from "./domain/conversionSeed";
import { INITIAL_WORKSPACE, workspaceReducer } from "./workspace";

describe("workspaceReducer", () => {
  it("owns open and export operation transitions", () => {
    const opening = workspaceReducer(INITIAL_WORKSPACE, { type: "open_started", message: "Opening" });
    const failed = workspaceReducer(opening, { type: "open_failed", message: "Bad file" });

    expect(opening.operation).toEqual({ type: "opening", message: "Opening" });
    expect(failed).toMatchObject({ openError: "Bad file", operation: { type: "idle" } });
  });

  it("resets view state for a newly opened document", () => {
    const previous = { ...INITIAL_WORKSPACE, page: 2 as const, exportError: "Old failure" };
    const opened = workspaceReducer(previous, { type: "open_succeeded", project: project("full"), adapterLabel: "Test adapter" });

    expect(opened).toMatchObject({ page: 0, operation: { type: "idle" } });
    expect(opened.session?.document.origin.adapterLabel).toBe("Test adapter");
  });

  it("opens metadata when the imported model has no usable preview", () => {
    expect(workspaceReducer(INITIAL_WORKSPACE, {
      type: "open_succeeded",
      project: project("unavailable"),
      adapterLabel: "Test adapter",
    }).page).toBe(1);
  });
});

function project(preview: "full" | "unavailable"): ImportedProject {
  return {
    source: "bedrock_animation_json",
    sourceName: "unknown.json",
    suggestedMetadata: { name: "Unknown", description: "Unknown" },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes: { root: { id: "root", type: "anchor", defaultMatrix: IDENTITY_MATRIX } },
    animations: [{
      id: "unknown",
      name: "Unknown",
      durationTicks: 20,
      playbackMode: "once",
      loopDelayTicks: 0,
      tracks: {},
      events: { start: [], timeline: [], loop: [], stop: [] },
      availability: preview === "full" ? undefined : { preview, exportable: false, reason: "No preview" },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
