import { describe, expect, it } from "vitest";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../../format/emoteAnimation";
import type { Schema3EmoteAnimation } from "./animationSchema3";
import { migrateSchema3Animation } from "./animationSchema3Migration";
import { requireSchema3Animation } from "./animationSchema3Runtime";
import { validateSchema3Animation } from "./animationSchema3Validator";

const IDENTITY: Matrix16 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

function animation(): Schema3EmoteAnimation {
  return {
    type: "animation",
    schema_version: 3,
    id: "demo:wave",
    metadata: { name: "Wave", description: "Schema 3 emote." },
    settings: {
      standalone: true,
      cooldown: "0t",
      player: createDefaultPlayerBehavior(),
      playback: { mode: "server_sync", loop_delay: "2t" },
    },
    nodes: {
      arm: {
        type: "item_display",
        space: "partner",
        item_stack_snbt: "{id:\"minecraft:player_head\",count:1}",
        item_display: "none",
        default_matrix: IDENTITY,
        skin: { participant: "partner", part: "right_arm", order: 1 },
      },
    },
    timeline: {
      duration: "4t",
      keyframes: [
        { time: "0t", node_transforms: { arm: { matrix: IDENTITY } } },
        { time: "4t", node_transforms: { arm: { matrix: IDENTITY, interpolation_duration: "2t" } } },
      ],
    },
  };
}

describe("schema 3 animation migration", () => {
  it("parses and validates schema 3 before migrating it to schema 4", () => {
    const source = requireSchema3Animation(animation());

    expect(validateSchema3Animation(source)).toEqual([]);
    const migrated = migrateSchema3Animation(source);

    expect(migrated.schema_version).toBe(4);
    expect(migrated.nodes.arm.transform).toEqual({ position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] });
    expect(migrated.timeline.tracks.arm.position?.map((frame) => frame.time)).toEqual(["0t", "2t", "4t"]);
  });

  it("keeps schema 3 semantic validation inside the legacy import boundary", () => {
    const source = animation();
    source.timeline.keyframes[1].node_transforms!.arm.interpolation_duration = "5t";

    expect(validateSchema3Animation(source)).toContainEqual({
      path: "timeline.keyframes[1].node_transforms.arm.interpolation_duration",
      message: "exceeds the time since the previous node transform",
    });
  });
});
