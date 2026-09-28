import { describe, expect, it } from "vitest";
import type { ImportedProject } from "../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../format/emoteAnimation";
import type { ImportAdapterLoader } from "./adapter";
import { importFileBatch, type ImportFile } from "./importBatch";

const adapter: ImportAdapterLoader = {
  id: "bedrock_animation_json",
  label: "Test animation",
  extensions: ["test"],
  load: async () => ({
    id: "bedrock_animation_json",
    label: "Test animation",
    extensions: ["test"],
    probe: () => ({ confidence: 100, reason: "test file" }),
    import: async (input): Promise<ImportedProject> => {
      if (input.name === "invalid.test") throw new Error("Invalid animation data.");
      return {
        source: "bedrock_animation_json",
        sourceName: input.name,
        suggestedMetadata: { name: "Test", description: "Test animation" },
        suggestedPlayer: createDefaultPlayerBehavior(),
        nodes: {},
        animations: [{
          id: "test",
          name: "Test",
          durationTicks: 20,
          playbackMode: "once",
          loopDelayTicks: 0,
          events: { start: [], timeline: [], loop: [], stop: [] },
          preview: { durationTicks: 20, tracks: {}, availability: { status: "full" } },
          exportAvailability: { exportable: true },
          runtime: { kind: "baked", tracks: {} },
        }],
        diagnostics: [],
        resources: new Map(),
      };
    },
  }),
};

function file(name: string): ImportFile {
  return { name, arrayBuffer: async () => new ArrayBuffer(0) };
}

describe("file batch import", () => {
  it("opens successful files and reports read and import failures separately", async () => {
    const unreadable: ImportFile = { name: "unreadable.test", arrayBuffer: async () => { throw new Error("Read failed."); } };
    const document = await importFileBatch([file("first.test"), unreadable, file("invalid.test"), file("second.test")], [adapter]);

    expect(document.animations).toHaveLength(2);
    expect(document.origin.sourceName).toBe("first.test, second.test");
    expect(document.diagnostics).toEqual([
      expect.objectContaining({ code: "file_import_failed", sourcePath: "unreadable.test", message: expect.stringContaining("Read failed.") }),
      expect.objectContaining({ code: "file_import_failed", sourcePath: "invalid.test", message: expect.stringContaining("Invalid animation data.") }),
    ]);
  });

  it("reports the failures when no animation file can be opened", async () => {
    await expect(importFileBatch([file("invalid.test")], [adapter]))
      .rejects.toThrow("invalid.test: Invalid animation data.");
  });
});
