import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { geckoLibBbmodelAdapter } from "./geckoLibBbmodelAdapter";

const REPOSITORY_ROOT = fileURLToPath(new URL("../../../../", import.meta.url));

describe("GeckoLib animation pipeline", () => {
  it("projects preview and native runtime independently from runtime Molang", async () => {
    const path = "docs/reference/bbmodel/emote.bbmodel";
    const project = await geckoLibBbmodelAdapter.import({ name: "emote.bbmodel", bytes: await readFile(resolve(REPOSITORY_ROOT, path)) });
    const animation = project.animations.find((candidate) => candidate.name === "indicate");

    expect(animation).toBeDefined();
    expect(Object.keys(animation!.preview.tracks).length).toBeGreaterThan(0);
    expect(JSON.stringify(animation!.preview.tracks)).not.toMatch(/q\.(?:loop_count|target_[xy]_rotation)/);
    expect(animation!.runtime.kind).toBe("native");
    if (animation!.runtime.kind !== "native") return;
    expect(animation!.runtime.tracks).not.toBe(animation!.preview.tracks);
    expect(JSON.stringify(animation!.runtime.tracks)).toMatch(/q\.(?:loop_count|target_[xy]_rotation)/);
  });
});
