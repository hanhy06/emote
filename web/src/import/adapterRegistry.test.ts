import { describe, expect, it, vi } from "vitest";
import type { ImportAdapter, ImportAdapterLoader } from "./adapter";
import type { ImportSource } from "../domain/conversionSeed";
import { detectAdapter, importDetected } from "./adapterRegistry";
import { ConversionError } from "../foundation/diagnostics";

function adapter(id: string, confidence: number, extensions: readonly string[] = []): ImportAdapter {
  return {
    id: id as ImportSource,
    label: id,
    extensions,
    probe: () => ({ confidence, reason: id }),
    import: async () => { throw new Error("not used"); },
  };
}

function loader(value: ImportAdapter): ImportAdapterLoader {
  return { id: value.id, label: value.label, extensions: value.extensions, description: value.label, load: vi.fn(async () => value) };
}

describe("detectAdapter", () => {
  const input = { name: "input.bin", bytes: new Uint8Array() };

  it("selects the highest-confidence adapter", async () => {
    const result = await detectAdapter([loader(adapter("weak", 20)), loader(adapter("strong", 100))], input);
    expect(result.adapter.id).toBe("strong");
  });

  it("rejects an ambiguous highest-confidence match", async () => {
    await expect(detectAdapter([loader(adapter("first", 100)), loader(adapter("second", 100))], input)).rejects.toThrow("ambiguous");
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

  it("probes only adapters matching a recognized extension", async () => {
    const json = adapter("json", 100, ["json"]);
    const zip = adapter("zip", 100, ["zip"]);
    zip.probe = vi.fn(zip.probe);

    const result = await detectAdapter([loader(json), loader(zip)], { name: "input.json", bytes: new Uint8Array() });

    expect(result.adapter.id).toBe("json");
    expect(zip.probe).not.toHaveBeenCalled();
  });

  it("falls back to content detection when extension matches fail", async () => {
    const json = adapter("json", 0, ["json"]);
    const model = adapter("model", 100, ["bbmodel"]);
    model.probe = vi.fn(model.probe);

    const result = await detectAdapter([loader(json), loader(model)], { name: "renamed.json", bytes: new Uint8Array() });

    expect(result.adapter.id).toBe("model");
    expect(model.probe).toHaveBeenCalledOnce();
  });
});
