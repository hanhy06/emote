import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
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
    expect(Object.keys(imported.nodes)).toEqual(["body", "head", "left_arm", "right_arm", "left_leg", "right_leg"]);
    expect(imported.nodes.left_arm.type === "item_display" && imported.nodes.left_arm.suggestedSkin).toEqual({ part: "left_arm", order: 0 });
    expect(imported.nodes.body.defaultMatrix[7]).toBeCloseTo(1.40625);
    expect(imported.animations[0].durationTicks).toBe(25);
    expect(imported.animations[0].loop).toBe("hold");
    expect(imported.animations[0].tracks.body.transforms[0].matrix[7]).toBeCloseTo(1.5234375);
    expect(imported.animations[0].tracks.left_arm.transforms[0].matrix).not.toEqual(imported.nodes.left_arm.defaultMatrix);

    const compiled = compileImportedProject(imported, {});
    expect(compiled).toHaveLength(1);
    expect(compiled[0].nodes.body.type === "item_display" && compiled[0].nodes.body.skin).toEqual({ participant: "initiator", part: "body", order: 0 });
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
    expect(animation.tracks.body.transforms.map((frame) => frame.tick)).toEqual([0, 1, 2, 3, 4]);
    const preserved = animation.tracks.body.transforms.find((frame) => frame.interpolation.type === "step" && frame.tick > 0);
    expect([1, 2]).toContain(preserved?.tick);
    expect(preserved?.matrix[7]).toBeCloseTo(1.640625);
    expect(animation.tracks.right_arm.transforms[3].matrix.every(Number.isFinite)).toBe(true);
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
    expect(imported.animations[0].tracks.body.transforms[10].matrix).not.toEqual(imported.nodes.body.defaultMatrix);
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

    expect(imported.animations[0].durationTicks).toBe(20);
    expect(imported.animations[0].tracks.body.transforms.map((frame) => frame.tick)).toEqual(
      Array.from({ length: 21 }, (_, tick) => tick),
    );
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({
      code: "bedrock_animation_duration_assumed",
      message: expect.stringContaining("set animations.animation.emote.missing_length.animation_length"),
    }));
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

  it("opens a file containing only unsupported runtime Molang for metadata editing", async () => {
    const imported = await bedrockAnimationAdapter.import(input(JSON.stringify({
      format_version: "1.8.0",
      animations: {
        runtime: { bones: { body: { rotation: [0, "q.is_on_ground * 45", 0] } } },
      },
    })));

    expect(imported.animations).toHaveLength(1);
    expect(imported.animations[0]).toMatchObject({
      name: "runtime",
      tracks: {},
      availability: { preview: "create_pose", exportable: true },
    });
    expect(imported.diagnostics[0].message).toContain("replace the expression at runtime.body.rotation[1]");
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "runtime" });
    expect(compiled.timeline).toMatchObject({ duration: "20t", tracks: {} });
  });

  it("silently hides left item, right item, and cape helper bones", async () => {
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
    expect(Object.keys(imported.nodes)).toEqual(["body", "head", "left_arm", "right_arm", "left_leg", "right_leg"]);
  });
});

function input(text: string) {
  return { name: "pose.animation.json", bytes: encoder.encode(text) };
}
