import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior } from "./format/emoteAnimation";
import { IDENTITY_MATRIX } from "./format/matrix";
import type { ImportedProject } from "./domain/conversionSeed";
import { createConverterSession } from "./converterSession";
import { INITIAL_WORKSPACE, workspaceReducer } from "./workspace";

describe("workspaceReducer", () => {
  it("owns open and export operation transitions", () => {
    const opening = workspaceReducer(INITIAL_WORKSPACE, { type: "begin_open", message: "Opening" });
    const failed = workspaceReducer(opening, { type: "fail_open", message: "Bad file" });

    expect(opening.operation).toEqual({ type: "opening", message: "Opening" });
    expect(failed).toMatchObject({ openError: "Bad file", operation: { type: "idle" } });
  });

  it("resets view state for a newly opened document", () => {
    const previous = { ...INITIAL_WORKSPACE, page: 2 as const, exportError: "Old failure" };
    const session = createConverterSession(project("full"), "Test adapter");
    const opened = workspaceReducer(previous, { type: "finish_open", session });

    expect(opened).toMatchObject({ session, page: 0, operation: { type: "idle" } });
  });

  it("opens metadata when the imported model has no usable preview", () => {
    const session = createConverterSession(project("unavailable"), "Test adapter");

    expect(workspaceReducer(INITIAL_WORKSPACE, { type: "finish_open", session }).page).toBe(1);
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
      loop: "once",
      loopDelayTicks: 0,
      tracks: {},
      events: { start: [], timeline: [], loop: [], stop: [] },
      availability: preview === "full" ? undefined : { preview, exportable: false, reason: "No preview" },
    }],
    diagnostics: [],
    resources: new Map(),
  };
}
