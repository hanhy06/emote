import { describe, expect, it } from "vitest";
import { isBedrockAnimationDocument, requireBedrockAnimationDocument } from "./bedrockAnimationSchema";

describe("Bedrock animation schema", () => {
  it("accepts Persona 1.8.0 animation channels and Bedrock keyframe forms", () => {
    const document = {
      format_version: "1.8.0",
      animations: {
        "animation.emote.wave": {
          loop: "hold_on_last_frame",
          animation_length: 1.25,
          anim_time_update: "q.anim_time + q.delta_time * 1.5",
          bones: {
            leftArm: {
              rotation: {
                "0.0": [0, 0, 0],
                "0.37": { pre: [0, 0, 0], post: [45, 0, 0], lerp_mode: "catmullrom" },
              },
            },
          },
        },
      },
    };

    expect(isBedrockAnimationDocument(document)).toBe(true);
    expect(requireBedrockAnimationDocument(document)).toBe(document);
  });

  it("does not identify empty or differently versioned JSON as Bedrock animation input", () => {
    expect(isBedrockAnimationDocument({ format_version: "1.8.0", animations: {} })).toBe(false);
    expect(isBedrockAnimationDocument({ format_version: "1.10.0", animations: { test: {} } })).toBe(false);
  });

  it("rejects invalid timestamps and interpolation modes", () => {
    expect(() => requireBedrockAnimationDocument({
      format_version: "1.8.0",
      animations: { test: { bones: { body: { rotation: { nope: [0, 0, 0] } } } } },
    })).toThrow(/non-negative timestamp/);
    expect(() => requireBedrockAnimationDocument({
      format_version: "1.8.0",
      animations: { test: { bones: { body: { rotation: { "0": { post: [0, 0, 0], lerp_mode: "bezier" } } } } } },
    })).toThrow(/linear.*catmullrom/);
  });
});
