import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type EmoteAnimation } from "./emoteAnimation";
import { serializeEmoteAnimation } from "./serializer";
import { MAX_ANIMATION_DURATION_TICKS } from "./time";
import { validateEmoteAnimation } from "./validator";

function animation(): EmoteAnimation {
  return {
    type: "animation",
    schema_version: 4,
    id: "emote:test",
    metadata: { name: "Test", description: "Test emote." },
    settings: {
      standalone: true,
      cooldown: "0t",
      player: createDefaultPlayerBehavior(),
      playback: { mode: "once", loop_delay: "0t" },
    },
    nodes: {
      display: {
        type: "item_display", space: "scene", item_stack_snbt: "{id:\"minecraft:stone\",count:1}", item_display: "none",
        transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
      },
      effect: { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } },
    },
    timeline: {
      duration: "2t",
      tracks: { display: { position: [{ time: "0t", value: [0, 0, 0] }] } },
    },
  };
}

describe("validateEmoteAnimation", () => {
  it("accepts and serializes a structurally valid animation", () => {
    const value = animation();
    expect(validateEmoteAnimation(value)).toEqual([]);
    expect(serializeEmoteAnimation(value)).toContain('"schema_version":4');
  });

  it("rejects Molang functions unavailable in the mod", () => {
    const value = animation();
    value.molang = { initialize: "v.speed = math.sign(-2);" };
    value.timeline.tracks.display.position![0].value = ["query.any(1, 1)", "math.hermite_blend(0.5)", 0];

    expect(validateEmoteAnimation(value)).toEqual(expect.arrayContaining([
      {
        path: "molang.initialize",
        message: "uses Molang function math.sign, which is not supported by the Emote mod",
      },
      {
        path: "timeline.tracks.display.position[0].value[0]",
        message: "uses Molang function query.any, which is not supported by the Emote mod",
      },
    ]));
  });

  it("validates a child skin participant against its inherited root space", () => {
    const value = animation();
    value.nodes.root = {
      type: "anchor",
      space: "partner",
      transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] },
    };
    const display = value.nodes.display;
    if (display.type !== "item_display") throw new Error("Expected item display fixture.");
    delete display.space;
    display.parent = "root";
    display.skin = { participant: "initiator", part: "head", order: 0 };

    expect(validateEmoteAnimation(value)).toContainEqual({
      path: "nodes.display.skin.participant",
      message: "must match the node space",
    });
  });

  it("accepts Minecraft time units", () => {
    const value = animation();
    value.settings.cooldown = "10s";
    value.timeline.duration = "0.5s";
    expect(validateEmoteAnimation(value)).toEqual([]);
  });

  it("accepts hold mode only with zero loop delay", () => {
    const value = animation();
    value.settings.playback = { mode: "hold", loop_delay: "0t" };
    expect(validateEmoteAnimation(value)).toEqual([]);
    value.settings.playback.loop_delay = "1t";
    expect(validateEmoteAnimation(value).map((issue) => issue.path)).toContain("settings.playback.loop_delay");
  });

  it("rejects anchor command sources and out-of-range timeline events", () => {
    const value = animation();
    value.timeline.events = { timeline: [{
      time: "2t",
      source: { type: "node", node: "effect" },
      origin: { type: "root" },
      commands: ["say test"],
    }] };
    const paths = validateEmoteAnimation(value).map((issue) => issue.path);
    expect(paths).toContain("timeline.events.timeline[0].time");
    expect(paths).toContain("timeline.events.timeline[0].source.node");
  });

  it("rejects invalid time strings and descending timeline events", () => {
    const value = animation();
    value.timeline.duration = "1m";
    value.settings.playback = { mode: "loop", loop_delay: "2147483648t" };
    value.timeline.tracks.display.position = [
      { time: "0t", value: [0, 0, 0], interpolation: "linear" },
      { time: "2147483648t", value: [1, 0, 0] },
    ];
    value.timeline.events = { timeline: [
      { time: "1t", source: { type: "player" }, origin: { type: "root" }, commands: [] },
      { time: "0t", source: { type: "player" }, origin: { type: "root" }, commands: [] },
    ] };

    const paths = validateEmoteAnimation(value).map((issue) => issue.path);
    expect(paths).toContain("timeline.duration");
    expect(paths).toContain("settings.playback.loop_delay");
    expect(paths).toContain("timeline.tracks.display.position[1].time");
    expect(paths).toContain("timeline.events.timeline[1].time");
  });

  it("rejects animations longer than ten minutes", () => {
    const value = animation();
    value.timeline.duration = `${MAX_ANIMATION_DURATION_TICKS + 1}t`;
    expect(validateEmoteAnimation(value)).toContainEqual({
      path: "timeline.duration",
      message: `must not exceed ${MAX_ANIMATION_DURATION_TICKS} ticks`,
    });
  });

  it("rejects an invalid movement stop distance", () => {
    const value = animation();
    value.settings.player.stop_conditions.movement_distance = Number.NaN;
    expect(validateEmoteAnimation(value).map((issue) => issue.path))
      .toContain("settings.player.stop_conditions.movement_distance");
  });

  it("allows timeline events with the same time in source order", () => {
    const value = animation();
    value.timeline.events = { timeline: [
      { time: "1t", source: { type: "player" }, origin: { type: "root" }, commands: ["say first"] },
      { time: "1t", source: { type: "player" }, origin: { type: "root" }, commands: ["say second"] },
    ] };
    expect(validateEmoteAnimation(value)).toEqual([]);
  });
});
