import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type EmoteAnimation, type EmoteNodeTracks, type EmoteVectorKeyframe } from "./emoteAnimation";
import { serializeEmoteAnimation } from "./serializer";
import { validateEmoteAnimation } from "./validator";
import { bakeSchema4Preview } from "../import/emoteJson/schema4PreviewBaker";

function animation(tracks: EmoteNodeTracks): EmoteAnimation {
  return {
    type: "animation", schema_version: 4, id: "test:cleanup", metadata: { name: "Cleanup", description: "" },
    settings: { standalone: true, cooldown: "0t", rotation_deadzone: 0, player: createDefaultPlayerBehavior(), playback: { mode: "loop", loop_start: "2t" } },
    nodes: { root: { type: "text_display", text: "Test", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } } },
    timeline: { duration: "8t", tracks: { root: tracks } },
  };
}

function frames(values: number[], interpolation: "step" | "linear" = "linear"): EmoteVectorKeyframe[] {
  return values.map((value, index) => ({ time: `${index * 2}t`, value: [value, 0, 0], ...(index < values.length - 1 && interpolation !== "linear" ? { interpolation } : {}) }));
}

function exportAndCompare(source: EmoteAnimation): EmoteAnimation {
  const snapshot = structuredClone(source);
  const output = JSON.parse(serializeEmoteAnimation(source)) as EmoteAnimation;
  expect(source).toEqual(snapshot);
  expect(validateEmoteAnimation(output)).toEqual([]);
  expect(bakeSchema4Preview(output)).toEqual(bakeSchema4Preview(source));
  expect(output.timeline.duration).toBe(source.timeline.duration);
  expect(output.settings).toEqual(source.settings);
  return output;
}

describe("final keyframe cleanup", () => {
  it("compacts explicit default interpolation without changing playback", () => {
    const position = frames([0, 2, 1, 5]).map((frame, i) => ({ ...frame, ...(i < 3 ? { interpolation: "linear" as const, easing: "linear" as const } : {}) }));
    const output = exportAndCompare(animation({ position }));
    expect(output.timeline.tracks.root.position?.every((frame) => frame.interpolation === undefined && frame.easing === undefined)).toBe(true);
  });

  it("removes matrix decomposition noise from constant scale without mutating the input", () => {
    const source = animation({ scale: [1, 1 + 2e-15, 1 - 1e-15, 1].map((value, i) => ({ time: `${i * 2}t`, value: [value, 1, 1] })) });
    const snapshot = structuredClone(source);
    const output = JSON.parse(serializeEmoteAnimation(source)) as EmoteAnimation;
    expect(source).toEqual(snapshot);
    expect(output.timeline.tracks).toEqual({});
    const oldPreview = bakeSchema4Preview(source).root.transforms;
    const newPreview = bakeSchema4Preview(output).root.transforms;
    oldPreview.forEach((frame, i) => frame.matrix.forEach((value, axis) => expect(newPreview[i].matrix[axis]).toBeCloseTo(value, 9)));
  });

  it("removes display nodes smaller than 0.001 after their motion tracks become empty", () => {
    const source = animation({ position: frames([0, 0, 0]) });
    source.nodes.root.transform.scale = [0.0009, -0.0009, 0];
    source.nodes.scene = { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } };

    const output = JSON.parse(serializeEmoteAnimation(source)) as EmoteAnimation;
    expect(output.nodes).toEqual({ scene: source.nodes.scene });
    expect(output.timeline.tracks).toEqual({});
    expect(validateEmoteAnimation(output)).toEqual([]);
  });

  it.each([
    { name: "a node on the 0.001 boundary", scale: [0.001, 0, 0] as const, tracks: { position: frames([0, 0]) } },
    { name: "a moving tiny node", scale: [0, 0, 0] as const, tracks: { position: frames([0, 1, 2]) } },
  ])("keeps $name", ({ scale, tracks }) => {
    const source = animation(tracks);
    source.nodes.root.transform.scale = scale;
    source.nodes.scene = { type: "anchor", space: "scene", transform: { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] } };

    const output = JSON.parse(serializeEmoteAnimation(source)) as EmoteAnimation;
    expect(output.nodes.root).toBeDefined();
    expect(validateEmoteAnimation(output)).toEqual([]);
  });

  it("removes default channels and empty node tracks", () => {
    const output = exportAndCompare(animation({ position: frames([0, 0, 0]), rotation: frames([0, 0]), visible: [{ time: "0t", value: true }, { time: "2t", value: true }] }));
    expect(output.timeline.tracks).toEqual({});
  });

  it("reduces linear position and scale samples and retains non-default constants", () => {
    const output = exportAndCompare(animation({ position: frames([0, 2, 4, 6, 8]), scale: frames([2, 2, 2]) }));
    expect(output.timeline.tracks.root.position).toEqual(frames([0, 8]).map((frame, index) => ({ ...frame, time: `${index * 8}t` })));
    expect(output.timeline.tracks.root.scale).toEqual([{ time: "0t", value: [2, 0, 0] }]);
  });

  it("keeps hold boundaries and removes only repeated step states", () => {
    const source = animation({ position: frames([0, 0, 4, 4, 4]), rotation: frames([0, 0, 30, 30, 60], "step") });
    const output = exportAndCompare(source);
    expect(output.timeline.tracks.root.position?.map((frame) => frame.time)).toEqual(["0t", "2t", "4t"]);
    expect(output.timeline.tracks.root.rotation?.map((frame) => frame.time)).toEqual(["0t", "4t", "8t"]);
  });

  it("preserves eased motion, changing quaternion rotations, and pre/post boundaries", () => {
    const position = frames([0, 2, 4]);
    position[0].easing = "ease_in_quad";
    const source = animation({ position, rotation: frames([0, 90, 180]), scale: [
      { time: "0t", pre: [1, 1, 1], post: [2, 2, 2] }, { time: "4t", value: [2, 2, 2] },
    ] });
    expect(exportAndCompare(source).timeline.tracks).toEqual(source.timeline.tracks);
  });

  it("removes repeated visibility values but keeps changes and initial state", () => {
    const output = exportAndCompare(animation({ visible: [true, true, false, false, true].map((value, index) => ({ time: `${index * 2}t`, value })) }));
    expect(output.timeline.tracks.root.visible?.map((frame) => frame.time)).toEqual(["0t", "4t", "8t"]);
  });

  it("preserves all keyframe timing when Molang can observe other channels", () => {
    const source = animation({ position: frames([0, 0, 0]), scale: [{ time: "0t", value: ["1 + q.key_frame_lerp_time", 1, 1] }] });
    expect(JSON.parse(serializeEmoteAnimation(source))).toEqual(source);
  });

  it("preserves repeated NBT patches and commands while cleaning transforms", () => {
    const source = animation({ position: frames([0, 0]), nbt: [{ time: "0t", value: "{Glowing:1b}" }, { time: "4t", value: "{Glowing:1b}" }] });
    source.timeline.events = { timeline: [0, 4].map((tick) => ({ time: `${tick}t`, source: { type: "server" }, origin: { type: "root" }, commands: ["say test"] })) };
    const output = exportAndCompare(source);
    expect(output.timeline.tracks.root.nbt).toEqual(source.timeline.tracks.root.nbt);
    expect(output.timeline.events).toEqual(source.timeline.events);
    expect(output.timeline.tracks.root.position).toBeUndefined();
  });
});
