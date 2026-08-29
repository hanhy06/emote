import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { validateEmoteAnimation } from "../../format/validator";
import { bedrockAnimationAdapter } from "./bedrockAnimationAdapter";

const encoder = new TextEncoder();

describe("bedrockAnimationAdapter", () => {
  it("detects commented Bedrock 1.8.0 animation documents only", () => {
    const bedrock = input(`{
      // Resource-pack JSON may be commented.
      "format_version": "1.8.0",
      "animations": { "animation.emote.pose": {} },
    }`);
    expect(bedrockAnimationAdapter.probe(bedrock)).toEqual({
      confidence: 100,
      reason: "matches a Bedrock 1.8.0 animation document",
    });
    expect(bedrockAnimationAdapter.probe(input('{"format_version":"1.8.0","animations":{}}'))).toMatchObject({ confidence: 0 });
  });

  it("imports a static player pose using the official player pivots and six skin parts", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        "animation.emote.pose": {
          loop: "hold_on_last_frame",
          animation_length: 1.25,
          bones: {
            body: { position: [0, 2, 0] },
            leftarm: { rotation: [90, 0, 0] },
          },
        },
      },
    })));

    expect(imported.source).toBe("bedrock_animation_json");
    expect(Object.keys(imported.nodes)).toEqual([
      "body_0", "body_1", "head", "left_arm_0", "left_arm_1", "right_arm_0", "right_arm_1",
      "left_leg_0", "left_leg_1", "right_leg_0", "right_leg_1",
    ]);
    expect(imported.nodes.left_arm_0.type === "item_display" && imported.nodes.left_arm_0.suggestedSkin).toEqual({ part: "left_arm", order: 0 });
    expect(imported.nodes.left_arm_1.type === "item_display" && imported.nodes.left_arm_1.suggestedSkin).toEqual({ part: "left_arm", order: 1 });
    expect(imported.nodes.left_arm_0.defaultMatrix[3]).toBeCloseTo(-0.29296875);
    expect(imported.nodes.right_arm_0.defaultMatrix[3]).toBeCloseTo(0.29296875);
    expect(imported.nodes.left_arm_0.type === "item_display" && imported.nodes.left_arm_0.playerHeadConversion?.matrix[3]).toBeCloseTo(-0.0625);
    expect(imported.nodes.right_arm_0.type === "item_display" && imported.nodes.right_arm_0.playerHeadConversion?.matrix[3]).toBeCloseTo(0.0625);
    expect(imported.nodes.body_0.defaultMatrix[7]).toBeCloseTo(1.40625);
    expect(imported.animations[0].durationTicks).toBe(25);
    expect(imported.animations[0].loop).toBe("hold");
    expect(imported.animations[0].tracks.body_0.transforms[0].matrix[7]).toBeCloseTo(1.5234375);
    expect(imported.animations[0].tracks.left_arm_0.transforms[0].matrix).not.toEqual(imported.nodes.left_arm_0.defaultMatrix);

    const compiled = compileImportedProject(imported, {});
    expect(compiled).toHaveLength(1);
    expect(compiled[0].settings.rotation_deadzone).toBe(0);
    expect(compiled[0].nodes.body_0.type === "item_display" && compiled[0].nodes.body_0.skin).toEqual({ participant: "initiator", part: "body", order: 0 });
    expect(compiled[0].nodes.body_1.type === "item_display" && compiled[0].nodes.body_1.skin).toEqual({ participant: "initiator", part: "body", order: 1 });
    expect(compiled[0].nodes).not.toHaveProperty("right_item");
  });

  it("normalizes Bedrock position and rotation axes before producing canonical transforms", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        axes: {
          animation_length: 0.1,
          bones: { body: { position: [16, 0, 0], rotation: [10, 20, 30] } },
        },
      },
    })));

    expect(imported.animations[0].tracks.body_0.transforms[0].matrix[3]).toBeCloseTo(-0.9375);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "axes" });
    expect(compiled.nodes.body_y.parent).toBe("body_z");
    expect(compiled.nodes.body_x.parent).toBe("body_y");
    expect(compiled.timeline.tracks.body_z.position?.[0].value).toEqual([-1, 0.75, 0]);
    expect(compiled.timeline.tracks.body_z.rotation?.[0].value).toEqual([0, 0, 30]);
    expect(compiled.timeline.tracks.body_y.rotation?.[0].value).toEqual([0, -20, 0]);
    expect(compiled.timeline.tracks.body_x.rotation?.[0].value).toEqual([-10, 0, 0]);
  });

  it("preserves numeric Bedrock start delays before baked and runtime motion", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        delayed: {
          start_delay: "0.1",
          animation_length: 0.1,
          bones: { body: { position: [0, 2, 0], rotation: [0, "q.anim_time * 90", 0] } },
        },
      },
    })));

    const animation = imported.animations[0];
    expect(animation.durationTicks).toBe(4);
    expect(animation.tracks.body_0.transforms.map((frame) => frame.tick)).toEqual([0, 2, 3, 4]);
    expect(animation.tracks.body_0.transforms[0].matrix).toEqual(imported.nodes.body_0.defaultMatrix);
    expect(imported.diagnostics).toEqual([]);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "delayed" });
    expect(compiled.timeline.duration).toBe("4t");
    expect(compiled.timeline.tracks.body_z.position?.map((frame) => frame.time)).toEqual(["0t", "2t"]);
    expect(compiled.timeline.tracks.body_z.position?.[0].interpolation).toBe("step");
    expect(compiled.timeline.tracks.body_y.rotation?.[1].value?.[1]).toBe("-((math.max(0, q.anim_time - 0.1)) * 90)");
  });

  it("bakes linear, Catmull-Rom, pre/post, and off-grid keyframes at 20 TPS", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        "animation.emote.motion": {
          animation_length: 0.2,
          bones: {
            body: {
              position: {
                "0": [0, 0, 0],
                "0.075": { pre: [0, 2, 0], post: [0, 4, 0], lerp_mode: "catmullrom" },
                "0.2": [0, 0, 0],
              },
            },
            rightArm: { rotation: { "0": [0, 0, 0], "0.2": [90, 0, 0] } },
          },
        },
      },
    })));

    const animation = imported.animations[0];
    expect(animation.durationTicks).toBe(4);
    expect(animation.tracks.body_0.transforms.map((frame) => frame.tick)).toEqual([0, 1, 2, 3, 4]);
    const preserved = animation.tracks.body_0.transforms.find((frame) => frame.interpolation.type === "step" && frame.tick > 0);
    expect([1, 2]).toContain(preserved?.tick);
    expect(preserved?.matrix[7]).toBeCloseTo(1.640625);
    expect(animation.tracks.right_arm_0.transforms[3].matrix.every(Number.isFinite)).toBe(true);
  });

  it("bakes deterministic Molang time expressions and constant anim_time_update rates", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        "animation.emote.fast": {
          animation_length: 1,
          anim_time_update: "q.anim_time + q.delta_time * 2",
          bones: { root: { rotation: [0, "-query.anim_time * 90", 0] } },
        },
      },
    })));

    expect(imported.animations[0].durationTicks).toBe(10);
    expect(imported.animations[0].tracks.body_0.transforms[10].matrix).not.toEqual(imported.nodes.body_0.defaultMatrix);
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "fast" });
    expect(compiled.timeline.duration).toBe("10t");
    expect(compiled.timeline.tracks.root_y.rotation?.[0].value?.[1]).toBe("-(-(q.anim_time * 2) * 90)");
  });

  it("uses a warned 20-tick duration for time-dependent Molang without a source duration", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        "animation.emote.missing_length": {
          bones: { body: { rotation: [0, "q.anim_time * 90", 0] } },
        },
      },
    })));

    expect(imported.animations[0].durationTicks).toBe(12_000);
    expect(imported.animations[0].preview?.durationTicks).toBe(20);
    expect(imported.animations[0].preview?.tracks.body_0.transforms.map((frame) => frame.tick)).toEqual(
      Array.from({ length: 21 }, (_, tick) => tick),
    );
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({
      code: "bedrock_animation_duration_assumed",
      message: expect.stringContaining("animations.animation.emote.missing_length.animation_length"),
    }));
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "missing_length" });
    expect(compiled.timeline.duration).toBe("12000t");
    expect(compiled.settings.playback.mode).toBe("once");
    expect(compiled.timeline.tracks.body_y.rotation?.[0].value?.[1]).toBe("-(q.anim_time * 90)");
    expect(validateEmoteAnimation(compiled)).toEqual([]);
    const [looped] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "missing_length", loop: "loop" });
    expect(looped.settings.playback.mode).toBe("loop");
  });

  it("keeps supported animations and retains unsupported Molang as a Create pose", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        supported: { animation_length: 0.1, bones: { hat: { rotation: [0, 10, 0] }, body: { rotation: [0, 0, 0] } } },
        random: { bones: { body: { position: ["math.random(-1, 1)", 0, 0] } } },
      },
    })));

    expect(imported.animations.map((animation) => animation.name)).toEqual(["supported", "random"]);
    expect(imported.animations[1].availability).toMatchObject({ preview: "create_pose", exportable: true });
    expect(imported.diagnostics).toEqual(expect.arrayContaining([
      expect.objectContaining({ code: "bedrock_animation_bone_ignored" }),
      expect.objectContaining({ code: "bedrock_animation_molang_unavailable" }),
    ]));
  });

  it("previews supported runtime player queries without warning and preserves them for export", async () => {
    const playerQueries = [
      "q.target_x_rotation", "q.target_y_rotation",
      "q.body_x_rotation", "q.body_y_rotation",
      "q.head_x_rotation", "q.head_y_rotation",
      "q.eye_target_x_rotation", "q.eye_target_y_rotation",
      "q.modified_distance_moved", "q.walk_distance",
      "q.is_sneaking", "q.is_sleeping", "q.is_emoting", "q.item_is_charged", "q.sleep_rotation",
    ].join(" + ");
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        runtime: { bones: { body: { rotation: [0, playerQueries, 0] } } },
      },
    })));

    expect(imported.animations).toHaveLength(1);
    expect(imported.animations[0].availability).toBeUndefined();
    expect(imported.diagnostics).toEqual([]);
    expect(imported.animations[0].tracks.body_0.transforms[0].matrix).toEqual(expect.any(Array));
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "runtime" });
    expect(compiled.timeline.duration).toBe("1t");
    expect(compiled.timeline.tracks.body_y.rotation?.[0].value?.[1]).toBe(`-(${playerQueries})`);
  });

  it("preserves dynamic anim_time_update through a runtime Molang clock", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        dynamic_clock: {
          animation_length: 2,
          anim_time_update: "q.anim_time + q.delta_time * q.ground_speed",
          bones: { body: { rotation: [0, "q.anim_time * 45", 0] } },
        },
      },
    })));

    expect(imported.animations[0].availability).toMatchObject({ preview: "create_pose", exportable: true });
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "dynamic_clock" });
    expect(compiled.molang).toEqual({
      initialize: "v.bedrock_anim_time = 0;",
      tick: "v.bedrock_anim_time = (v.bedrock_anim_time + q.delta_time * q.ground_speed);",
    });
    expect(compiled.timeline.tracks.body_y.rotation?.[0].value?.[1]).toBe("-(v.bedrock_anim_time * 45)");
    expect(validateEmoteAnimation(compiled)).toEqual([]);
  });

  it("silently ignores item helper and cape bones", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        accessories: {
          animation_length: 0.1,
          bones: {
            left_item: { rotation: [10, 0, 0] },
            rightItem: { rotation: [0, 10, 0] },
            cape: { rotation: [0, 0, 10] },
          },
        },
      },
    })));

    expect(imported.animations[0].availability).toBeUndefined();
    expect(imported.diagnostics).toEqual([]);
    expect(Object.keys(imported.nodes)).toEqual([
      "body_0", "body_1", "head", "left_arm_0", "left_arm_1", "right_arm_0", "right_arm_1",
      "left_leg_0", "left_leg_1", "right_leg_0", "right_leg_1",
    ]);
    expect(imported.animations[0].tracks).not.toHaveProperty("left_item");
    expect(imported.animations[0].tracks).not.toHaveProperty("right_item");
  });
});

function input(text: string) {
  return { name: "pose.animation.json", bytes: encoder.encode(text) };
}
