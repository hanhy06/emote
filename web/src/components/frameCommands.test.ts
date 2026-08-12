import { describe, expect, it } from "vitest";
import type { ImportedAnimation } from "../import/types";
import { addFrameCommand, frameCommands, removeFrameCommand, updateFrameCommand } from "./frameCommands";

function animation(): ImportedAnimation {
  return {
    id: "test",
    name: "Test",
    durationTicks: 20,
    loop: "once",
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

describe("frame command editing", () => {
  it("lists only commands assigned to the selected frame", () => {
    expect(frameCommands(animation(), 2).map(({ command }) => command)).toEqual(["say one", "say two"]);
    expect(frameCommands(animation(), 3)).toEqual([]);
  });

  it("adds a server command event and preserves timeline order", () => {
    const updated = addFrameCommand(animation(), 5);

    expect(updated.events.timeline.map(({ tick }) => tick)).toEqual([2, 5, 8]);
    expect(frameCommands(updated, 5)[0]).toMatchObject({
      command: "",
      event: { source: { type: "server" }, origin: { type: "root" } },
    });
  });

  it("updates one command without changing its event settings", () => {
    const updated = updateFrameCommand(animation(), 0, 1, "particle flame ~ ~1 ~");

    expect(updated.events.timeline[0]).toEqual({
      tick: 2,
      source: { type: "server" },
      origin: { type: "root" },
      commands: ["say one", "particle flame ~ ~1 ~"],
    });
  });

  it("removes an empty event after its last command is removed", () => {
    const withoutFirst = removeFrameCommand(animation(), 0, 0);
    expect(withoutFirst.events.timeline[0].commands).toEqual(["say two"]);

    const withoutEvent = removeFrameCommand(withoutFirst, 0, 0);
    expect(withoutEvent.events.timeline.map(({ tick }) => tick)).toEqual([8]);
  });
});
