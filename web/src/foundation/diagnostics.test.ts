import { describe, expect, it } from "vitest";
import { ConversionError, conversionErrorMessage, groupConversionWarnings } from "./diagnostics";

describe("ConversionError", () => {
  it("preserves diagnostic code and source path", () => {
    const error = ConversionError.fromIssue({
      severity: "error",
      code: "unsupported_command",
      message: "Unsupported command.",
      sourcePath: "data/demo/function.mcfunction",
    });

    expect(error.code).toBe("unsupported_command");
    expect(error.sourcePath).toBe("data/demo/function.mcfunction");
    expect(conversionErrorMessage(error, "fallback")).toBe("Unsupported command. (data/demo/function.mcfunction)");
  });

  it("normalizes unknown failures at a boundary", () => {
    const error = ConversionError.fromUnknown("failure", "import_failed", "Could not import.", "input.json");

    expect(error).toMatchObject({ code: "import_failed", message: "Could not import.", sourcePath: "input.json" });
  });
});

describe("groupConversionWarnings", () => {
  it("groups warnings by code while preserving messages and source paths", () => {
    const groups = groupConversionWarnings([
      { severity: "warning", code: "bedrock_animation_molang_unavailable", message: "First", sourcePath: "animations.first" },
      { severity: "error", code: "broken", message: "Error" },
      { severity: "warning", code: "bedrock_animation_molang_unavailable", message: "Second", sourcePath: "animations.second" },
    ]);

    expect(groups).toEqual([{
      code: "bedrock_animation_molang_unavailable",
      label: "Bedrock Animation Molang Unavailable",
      issues: [
        { severity: "warning", code: "bedrock_animation_molang_unavailable", message: "First", sourcePath: "animations.first" },
        { severity: "warning", code: "bedrock_animation_molang_unavailable", message: "Second", sourcePath: "animations.second" },
      ],
    }]);
  });
});
