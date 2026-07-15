import { describe, expect, it } from "vitest";
import { ConversionError, conversionErrorMessage } from "./errors";

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
