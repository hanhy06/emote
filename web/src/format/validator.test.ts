import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type EmoteAnimation, type Matrix16 } from "./emoteAnimation";
import { serializeEmoteAnimation } from "./serializer";
import { MAX_ANIMATION_DURATION_TICKS } from "./time";
import { validateEmoteAnimation } from "./validator";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

function animation(): EmoteAnimation {
  return {
    type: "animation",
    schema_version: 3,
    id: "emote:test",
    metadata: { name: "Test", description: "Test emote." },
    settings: {
      standalone: true,
      cooldown: "0t",
      player: createDefaultPlayerBehavior(),
      playback: { mode: "once", loop_delay: "0t" },
    },
    nodes: {
      display: { type: "item_display", space: "scene", item_stack_snbt: "{id:\"minecraft:stone\",count:1}", item_display: "none", default_matrix: IDENTITY },
      effect: { type: "anchor", space: "scene", default_matrix: IDENTITY },
    },
    timeline: {
      duration: "2t",
      keyframes: [{ time: "0t", node_transforms: { display: { matrix: IDENTITY } } }],
    },
  };
}

describe("validateEmoteAnimation", () => {
  it("accepts and serializes a structurally valid animation", () => {
    const value = animation();
    expect(validateEmoteAnimation(value)).toEqual([]);
    expect(serializeEmoteAnimation(value)).toContain('"schema_version":3');
  });

  it("accepts Minecraft time units", () => {
    const value = animation();
    value.settings.cooldown = "10s";
    value.timeline.duration = "0.5s";
    expect(validateEmoteAnimation(value)).toEqual([]);
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
    value.timeline.keyframes[0].interpolation_duration = "2147483648t";
    value.timeline.events = { timeline: [
      { time: "1t", source: { type: "player" }, origin: { type: "root" }, commands: [] },
      { time: "0t", source: { type: "player" }, origin: { type: "root" }, commands: [] },
    ] };

    const paths = validateEmoteAnimation(value).map((issue) => issue.path);
    expect(paths).toContain("timeline.duration");
    expect(paths).toContain("settings.playback.loop_delay");
    expect(paths).toContain("timeline.keyframes[0].interpolation_duration");
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
