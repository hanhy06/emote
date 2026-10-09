import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { importFileBatch, type ImportFile } from "./importBatch";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { INITIAL_WORKSPACE, workspaceReducer } from "../workspace";

async function sampleFile(name: string, invalid = false): Promise<ImportFile> {
  const value = JSON.parse(await readFile(new URL("../../../docs/design/animation-v5.example.json", import.meta.url), "utf8"));
  if (invalid) value.animation.duration = -1;
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  return { name, arrayBuffer: async () => bytes.buffer };
}

describe("file batch import", () => {
  it("opens the existing v5 sample and reports read and import failures separately", async () => {
    const unreadable: ImportFile = { name: "unreadable.json", arrayBuffer: async () => { throw new Error("Read failed."); } };
    const document = await importFileBatch([await sampleFile("first.json"), unreadable, await sampleFile("invalid.json", true), await sampleFile("second.json")]);
    const expected = JSON.parse(await readFile(new URL("../../../docs/design/animation-v5.example.json", import.meta.url), "utf8"));

    expect(document.animations).toHaveLength(2);
    expect(document.origin.sourceName).toBe("first.json, second.json");
    expect(document.diagnostics).toEqual([
      expect.objectContaining({ code: "file_import_failed", sourcePath: "unreadable.json", message: expect.stringContaining("Read failed.") }),
      expect.objectContaining({ code: "file_import_failed", sourcePath: "invalid.json", message: expect.stringContaining("duration") }),
    ]);
    for (let index = 0; index < document.animations.length; index++) {
      const tracks = expected.animation.tracks.map((track: any) => ({ ...track, target: { ...track.target, node: `input_${index + 1}__${track.target.node}` } }));
      expect(compileConversionAnimationArtifact(document, index).animation.animation.tracks).toEqual(tracks);
    }
    const opening = workspaceReducer({ ...INITIAL_WORKSPACE, page: 2 }, { type: "open_started", message: "Opening" });
    const opened = workspaceReducer(opening, { type: "documents_open_succeeded", document });
    expect(opened.session?.document).toBe(document);
    expect(opened.session?.animationIndex).toBe(0);
    expect(opened.session?.previewFrameIndex).toBe(0);
    expect(opened.session?.selectedNodeIds.size).toBe(0);
    expect(opened.page).toBe(0);
    expect(opened.operation).toEqual({ type: "idle" });
    const selected = workspaceReducer({ ...opened, page: 2 }, { type: "animation_selected", index: 1 });
    expect(selected.session?.animationIndex).toBe(1);
    expect(selected.page).toBe(2);
  });

  it("reports the failure when the existing v5 sample cannot be imported", async () => {
    await expect(importFileBatch([await sampleFile("invalid.json", true)]))
      .rejects.toThrow(/invalid.json:.*duration/);
  });
});
