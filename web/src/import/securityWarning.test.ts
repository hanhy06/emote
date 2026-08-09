import { describe, expect, it } from "vitest";
import type { ImportedAnimation, ImportedProject } from "./types";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import { countImportedCommands } from "./securityWarning";

function projectWithCommands(...commands: string[][]): ImportedProject {
  const emptyEventGroups = (): ImportedAnimation["events"] => ({ start: [], timeline: [], loop: [], stop: [] });
  const animations = commands.map((animationCommands, index) => {
    const events = emptyEventGroups();
    events.start.push({
      source: { type: "server" },
      origin: { type: "root" },
      commands: animationCommands,
    });
    return {
      id: `animation-${index}`,
      name: `Animation ${index}`,
      durationTicks: 1,
      loop: "once" as const,
      loopDelayTicks: 0,
      tracks: {},
      events,
    };
  });

  return {
    source: "emote_json",
    sourceName: "test.json",
    suggestedMetadata: { name: "Test", description: "" },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes: {},
    animations,
    diagnostics: [],
    resources: new Map(),
  };
}

describe("countImportedCommands", () => {
  it("returns zero without a project or event commands", () => {
    expect(countImportedCommands(null)).toBe(0);
    expect(countImportedCommands(projectWithCommands([]))).toBe(0);
  });

  it("counts commands across every imported animation", () => {
    expect(countImportedCommands(projectWithCommands(["say one"], ["say two", "say three"]))).toBe(3);
  });

  it("counts commands in every event phase", () => {
    const project = projectWithCommands([]);
    const events = project.animations[0].events;
    const event = {
      source: { type: "server" as const },
      origin: { type: "root" as const },
      commands: ["say test"],
    };
    events.start.push(event);
    events.timeline.push({ ...event, tick: 0 });
    events.loop.push(event);
    events.stop.push(event);

    expect(countImportedCommands(project)).toBe(4);
  });
});
