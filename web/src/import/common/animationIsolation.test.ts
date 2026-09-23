import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { zipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { animatedJavaBlueprintAdapter } from "../animatedJava/animatedJavaBlueprintAdapter";
import { bdDatapackAdapter } from "../bdDatapack/bdDatapackAdapter";
import { bedrockAnimationAdapter } from "../bedrockAnimation/bedrockAnimationAdapter";
import { geckoLibBbmodelAdapter } from "../geckoLib/geckoLibBbmodelAdapter";

const REPOSITORY_ROOT = fileURLToPath(new URL("../../../../", import.meta.url));
const encoder = new TextEncoder();

async function sample(path: string): Promise<Record<string, unknown>> {
  return JSON.parse(await readFile(resolve(REPOSITORY_ROOT, path), "utf8")) as Record<string, unknown>;
}

function mixedAnimations(project: Record<string, unknown>): Record<string, unknown> {
  const source = (project.animations as Array<Record<string, unknown>>)[0];
  return {
    ...project,
    animations: [
      { ...source, name: "invalid_schema", length: "not a number" },
      { ...source, name: "invalid_import", length: -1 },
      { ...source, name: "valid_animation" },
    ],
  };
}

describe("animation failure isolation", () => {
  it("keeps the remaining GeckoLib animation after schema and import failures", async () => {
    const project = mixedAnimations(await sample("docs/reference/bbmodel/emote.bbmodel"));
    const imported = await geckoLibBbmodelAdapter.import({ name: "mixed.bbmodel", bytes: encoder.encode(JSON.stringify(project)) });

    expect(imported.animations.map((animation) => animation.name)).toEqual(["valid_animation"]);
    expect(imported.diagnostics.filter((issue) => issue.code === "animation_skipped"))
      .toEqual([
        expect.objectContaining({ sourcePath: "animations[0].length", message: expect.stringContaining("invalid_schema") }),
        expect.objectContaining({ sourcePath: "animations[1]", message: expect.stringContaining("invalid_import") }),
      ]);
  });

  it("fails the file when every GeckoLib animation is invalid", async () => {
    const project = await sample("docs/reference/bbmodel/emote.bbmodel");
    const animation = (project.animations as Array<Record<string, unknown>>)[0];
    project.animations = [{ ...animation, name: "invalid_only", length: "wrong" }];

    await expect(geckoLibBbmodelAdapter.import({ name: "invalid.bbmodel", bytes: encoder.encode(JSON.stringify(project)) }))
      .rejects.toThrow("No GeckoLib animations could be imported");
  });

  it("keeps AJ cube and display animation indices aligned after failures", async () => {
    const project = mixedAnimations(await sample("docs/reference/aj/emote.ajblueprint"));
    const imported = await animatedJavaBlueprintAdapter.import({ name: "mixed.ajblueprint", bytes: encoder.encode(JSON.stringify(project)) });

    expect(imported.animations.map((animation) => animation.name)).toEqual(["valid_animation"]);
    expect(imported.diagnostics.filter((issue) => issue.code === "animation_skipped"))
      .toEqual([
        expect.objectContaining({ sourcePath: "animations[0].length", message: expect.stringContaining("invalid_schema") }),
        expect.objectContaining({ sourcePath: "animations[1]", message: expect.stringContaining("invalid_import") }),
      ]);
  });

  it("keeps valid Bedrock animations when another entry fails validation", async () => {
    const imported = await bedrockAnimationAdapter.import({
      name: "mixed.animation.json",
      bytes: encoder.encode(JSON.stringify({
        format_version: "1.8.0",
        animations: {
          invalid: { animation_length: "wrong" },
          valid: { animation_length: 1 },
        },
      })),
    });

    expect(imported.animations.map((animation) => animation.name)).toEqual(["valid"]);
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({ code: "animation_skipped", sourcePath: "animations.invalid" }));
  });

  it("keeps valid BD Engine animations when another keyframe series is incomplete", async () => {
    const matrix = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1].join(",");
    const bytes = zipSync({
      "data/test/function/_/create.mcfunction": encoder.encode(`# Project created via BDEngine\nsummon minecraft:text_display ~ ~ ~ {id:"minecraft:text_display",Tags:["test_0"],transformation:[${matrix}],text:"hello"}`),
      "data/test/function/k/bad/keyframe_1.mcfunction": encoder.encode("# incomplete"),
      "data/test/function/k/good/keyframe_0.mcfunction": encoder.encode("# valid"),
    });
    const imported = await bdDatapackAdapter.import({ name: "mixed.zip", bytes });

    expect(imported.animations.map((animation) => animation.id)).toEqual(["good"]);
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({ code: "animation_skipped", message: expect.stringContaining("bad") }));
  });
});
