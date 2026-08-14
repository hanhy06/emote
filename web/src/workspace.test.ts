import { describe, expect, it } from "vitest";
import type { ConverterSession } from "./converterSession";
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
    const session = {} as ConverterSession;
    const opened = workspaceReducer(previous, { type: "finish_open", session });

    expect(opened).toMatchObject({ session, page: 0, operation: { type: "idle" } });
  });
});
