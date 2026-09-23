import { describe, expect, it } from "vitest";
import { importBedrockAnimationDocument } from "./bedrockAnimationImporter";

describe("Bedrock animation Molang policy", () => {
  it("preserves unknown Molang for export and warns about the Create pose preview", () => {
    const project = importBedrockAnimationDocument({
      format_version: "1.8.0",
      animations: {
        wave: {
          animation_length: 1,
          bones: { body: { rotation: ["q.unknown", 0, 0] } },
        },
      },
    }, "wave.animation.json");

    const animation = project.animations[0];
    expect(animation.preview.availability.status).toBe("create_pose");
    expect(project.diagnostics).toContainEqual(expect.objectContaining({
      severity: "warning",
      code: "molang_preview_limited",
    }));
    expect(animation.exportAvailability).toEqual({ exportable: true });
    expect(animation.runtime.kind).toBe("native");
    expect(JSON.stringify(animation.runtime)).toContain("q.unknown");
  });
});
