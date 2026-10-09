import { readInput } from "../formats";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { zipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { createConversionDocument } from "../../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../../compiler/animationCompiler";
import { evaluatePoseIR } from "../../domain/animationIRPose";

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
    const imported = await readInput("geckolib_bbmodel", { name: "mixed.bbmodel", bytes: encoder.encode(JSON.stringify(project)) });

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

    await expect(readInput("geckolib_bbmodel", { name: "invalid.bbmodel", bytes: encoder.encode(JSON.stringify(project)) }))
      .rejects.toThrow("No GeckoLib animations could be imported");
  });

  it("keeps AJ cube and display animation indices aligned after failures", async () => {
    const project = mixedAnimations(await sample("docs/reference/aj/emote.ajblueprint"));
    const imported = await readInput("animated_java_blueprint", { name: "mixed.ajblueprint", bytes: encoder.encode(JSON.stringify(project)) });

    expect(imported.animations.map((animation) => animation.name)).toEqual(["valid_animation"]);
    expect(imported.diagnostics.filter((issue) => issue.code === "animation_skipped"))
      .toEqual([
        expect.objectContaining({ sourcePath: "animations[0].length", message: expect.stringContaining("invalid_schema") }),
        expect.objectContaining({ sourcePath: "animations[1]", message: expect.stringContaining("invalid_import") }),
      ]);
  });

  it("keeps valid Bedrock animations when another entry fails validation", async () => {
    const imported = await readInput("bedrock_animation_json", {
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
    const files = {
      "data/test/function/_/create.mcfunction": encoder.encode(`# Project created via BDEngine\nsummon minecraft:text_display ~ ~ ~ {id:"minecraft:text_display",Tags:["test_0"],transformation:[${matrix}],text:"hello"}\nsummon minecraft:text_display ~ ~ ~ {id:"minecraft:text_display",Tags:["test_1"],transformation:[0.0001,0,0,0,0,0.0001,0,0,0,0,0.0001,0,0,0,0,1],text:"tiny"}`),
      "data/test/function/k/bad/keyframe_1.mcfunction": encoder.encode("# incomplete"),
      "data/test/function/k/good/keyframe_0.mcfunction": encoder.encode("# valid"),
      "data/test/function/k/good/keyframe_1.mcfunction": encoder.encode(`data merge entity @e[tag=test_0] {transformation:[1,0,0,2,0,1,0,0,0,0,1,0,0,0,0,1],interpolation_duration:1,text:"updated",line_width:120}`),
      "data/test/function/k/good/keyframe_2.mcfunction": encoder.encode(`data merge entity @e[tag=test_0] {transformation:[${matrix}],interpolation_duration:0}`),
    };
    const bytes = zipSync(files);
    const imported = await readInput("bd_datapack", { name: "mixed.zip", bytes });

    expect(imported.animations.map((animation) => animation.id)).toEqual(["good"]);
    expect(imported.animations[0].ir).toBeDefined();
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({ code: "animation_skipped", message: expect.stringContaining("bad") }));
    const output = compileConversionAnimationArtifact(createConversionDocument(imported, "BD Engine"), 0).animation;
    expect(Object.keys(output.nodes)).toEqual(["display_0"]);
    expect(output.nodes.display_1).toBeUndefined();
    const position = output.animation.tracks.find((track) => track.target.operation === "position");
    expect(position?.driver).toEqual({ type: "curve", before: "base", keys: [
      { time: 0, pre: [0, 0, 0], post: [0, 0, 0] },
      { time: 0.1, pre: [0, 0, 0], post: [2, 0, 0] },
      { time: 0.2, pre: [2, 0, 0], post: [0, 0, 0] },
      { time: 0.3, pre: [0, 0, 0], post: [0, 0, 0] },
    ], segments: [{ interpolation: "step" }, { interpolation: "step" }, { interpolation: "step" }] });
    const nbt = output.animation.tracks.find((track) => track.channel === "nbt");
    expect(nbt?.driver).toEqual({ type: "state", keys: [
      { time: 0, value: { merge: '{text:"hello",line_width:200}' } },
      { time: 0.1, value: { merge: '{text:"updated",line_width:120}' } },
    ] });
    const aj = await sample("docs/reference/aj/emote.ajblueprint");
    const anvil = (aj.animations as any[]).find((animation) => animation.name === "anvil");
    const command = (Object.values(anvil.animators) as any[]).flatMap((animator) => animator.keyframes ?? [])
      .find((frame) => frame.channel === "function").data_points[0].function as string;
    const framePath = "data/test/function/k/good/keyframe_1.mcfunction";
    const unknownMerge = `data merge entity @e[tag=external_actor] {transformation:[${matrix}]}`;
    const withCommands = await readInput("bd_datapack", { name: "mixed.zip", bytes: zipSync({ ...files,
      [framePath]: encoder.encode(`${new TextDecoder().decode(files[framePath])}\n${command}\n${unknownMerge}`),
    }) });
    const commandOutput = compileConversionAnimationArtifact(createConversionDocument(withCommands, "BD Engine"), 0).animation;
    expect(commandOutput.animation.tracks).toEqual(output.animation.tracks);
    expect(commandOutput.animation.events?.timeline).toEqual([command, unknownMerge].map((command) => ({ time: 0.1, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [command] } })));
    const commandWarnings = withCommands.diagnostics.filter((issue) => issue.code === "bd_datapack_command_approximated");
    expect(commandWarnings).toHaveLength(2);
    for (const issue of commandWarnings) {
      expect(issue.sourcePath).toBe(framePath);
      expect(issue.message).toContain("good at 2t");
      expect(issue.message).not.toContain(command);
      expect(issue.message).not.toContain(unknownMerge);
    }
    for (const [delay, beforeUpdate, atUpdate] of [[0, 0.5, 1], [1, 0, 0.5]]) {
      const framePath = "data/test/function/k/good/keyframe_1.mcfunction";
      const interrupted = await readInput("bd_datapack", { name: "mixed.zip", bytes: zipSync({ ...files,
        [framePath]: encoder.encode(new TextDecoder().decode(files[framePath]).replace("interpolation_duration:1", `interpolation_duration:4,start_interpolation:${delay}`)),
      }) });
      const ir = interrupted.animations[0].ir!;
      const at = (time: number) => evaluatePoseIR(ir, time, (value) => {
        if (typeof value !== "number") throw new Error("BD transforms must be literal.");
        return value;
      }).display_0.matrix.elements[12];
      expect(at(0.15)).toBeCloseTo(beforeUpdate, 8);
      const track = ir.animation.tracks.find((track) => track.target.operation === "position")!;
      if (track.driver.type !== "curve") throw new Error("Missing BD curve.");
      expect(track.driver.keys.find((key) => key.time === 0.2)?.pre?.[0]).toBeCloseTo(atUpdate, 8);
      expect(at(0.2)).toBe(0);
    }
  });
});
