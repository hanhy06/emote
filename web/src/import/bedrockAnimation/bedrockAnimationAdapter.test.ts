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
});

function input(text: string) {
  return { name: "pose.animation.json", bytes: encoder.encode(text) };
}
