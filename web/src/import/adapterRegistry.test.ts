import { describe, expect, it } from "vitest";
import type { ImportAdapter } from "./adapter";
import { detectAdapter, importDetected } from "./adapterRegistry";
import { ConversionError } from "./errors";

function adapter(id: string, confidence: number): ImportAdapter {
  return {
    id,
    label: id,
    extensions: [],
    probe: () => ({ confidence, reason: id }),
    import: async () => { throw new Error("not used"); },
  };
}

describe("detectAdapter", () => {
  const input = { name: "input.bin", bytes: new Uint8Array() };

  it("selects the highest-confidence adapter", async () => {
    const result = await detectAdapter([adapter("weak", 20), adapter("strong", 100)], input);
    expect(result.adapter.id).toBe("strong");
  });

  it("rejects an ambiguous highest-confidence match", async () => {
    await expect(detectAdapter([adapter("first", 100), adapter("second", 100)], input)).rejects.toThrow("ambiguous");
  });

  it("adds adapter and input context to import failures", async () => {
    const detected = { adapter: adapter("broken", 100), probe: { confidence: 100, reason: "test" } };
    detected.adapter.import = async () => { throw new Error("broken payload"); };

    const failure = importDetected(detected, input).catch((reason: unknown) => reason);
    await expect(failure).resolves.toMatchObject({
      code: "broken_import_failed",
      message: "broken payload",
      sourcePath: "input.bin",
    } satisfies Partial<ConversionError>);
  });
});
