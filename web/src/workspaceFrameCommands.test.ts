import { describe, expect, it } from "vitest";
import { frameCommands } from "./components/CommandPanel";
import type { ImportedAnimation, ImportedProject } from "./domain/conversionSeed";
import { createDefaultPlayerBehavior } from "./format/emoteAnimation";
import { INITIAL_WORKSPACE, workspaceReducer, type WorkspaceState } from "./workspace";

describe("frame command editing", () => {
  it("lists only commands assigned to the selected frame", () => {
    expect(frameCommands(animation(), 2).map(({ command }) => command)).toEqual(["say one", "say two"]);
    expect(frameCommands(animation(), 3)).toEqual([]);
  });

  it("adds a server command event and preserves timeline order", () => {
    const updated = workspaceReducer(opened(), { type: "frame_command_added", tick: 5 }).session!.document.animations[0].source;

    expect(updated.events.timeline.map(({ tick }) => tick)).toEqual([2, 5, 8]);
    expect(frameCommands(updated, 5)[0]).toMatchObject({
      command: "",
      event: { source: { type: "server" }, origin: { type: "root" } },
    });
  });

  it("updates one command without changing its event settings", () => {
    const updated = workspaceReducer(opened(), {
      type: "frame_command_changed",
      eventIndex: 0,
      commandIndex: 1,
      command: "particle flame ~ ~1 ~",
    }).session!.document.animations[0].source;

    expect(updated.events.timeline[0]).toEqual({
      tick: 2,
      source: { type: "server" },
      origin: { type: "root" },
      commands: ["say one", "particle flame ~ ~1 ~"],
    });
  });

  it("removes an empty event after its last command is removed", () => {
    const withoutFirst = workspaceReducer(opened(), { type: "frame_command_removed", eventIndex: 0, commandIndex: 0 });
    expect(withoutFirst.session!.document.animations[0].source.events.timeline[0].commands).toEqual(["say two"]);

    const withoutEvent = workspaceReducer(withoutFirst, { type: "frame_command_removed", eventIndex: 0, commandIndex: 0 });
    expect(withoutEvent.session!.document.animations[0].source.events.timeline.map(({ tick }) => tick)).toEqual([8]);
  });
});

function opened(): WorkspaceState {
  const project: ImportedProject = {
    source: "emote_json",
    sourceName: "test.json",
    suggestedMetadata: { name: "Test", description: "Test" },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes: {},
    animations: [animation()],
    diagnostics: [],
    resources: new Map(),
  };
  return workspaceReducer(INITIAL_WORKSPACE, { type: "open_succeeded", project, adapterLabel: "Test adapter" });
}

function animation(): ImportedAnimation {
  return {
    id: "test",
    name: "Test",
    durationTicks: 20,
    playbackMode: "once",
    loopDelayTicks: 0,
    tracks: {},
    events: {
      start: [],
      timeline: [
        { tick: 2, source: { type: "server" }, origin: { type: "root" }, commands: ["say one", "say two"] },
        { tick: 8, source: { type: "player" }, origin: { type: "root" }, commands: ["say later"] },
      ],
      loop: [],
      stop: [],
    },
  };
}
