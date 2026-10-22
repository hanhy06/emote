import { createTestDocument, importTestFileBatch } from "./testProject";
import { readFile } from "node:fs/promises";
import { zipSync } from "fflate";
import { Matrix4 } from "three";
import { expect, it } from "vitest";
import { evaluatePose } from "../domain/animationIRPose";
import { exportAnimation } from "../export/projectExporter";
import type { AnimationJson } from "../format/animation";
import { readInput } from "../import/formats";
import source from "./fixtures/bd-curves.json";

it("preserves sparse frame timing and commands without a generator comment or display nodes", async () => {
  const encoder = new TextEncoder();
  for (const hasDisplay of [false, true]) {
    const bytes = zipSync({
      "data/bd/function/_/create.mcfunction": encoder.encode(hasDisplay
        ? `summon minecraft:block_display ~ ~ ~ {id:"minecraft:block_display",Tags:["bd_0"],block_state:{Name:"minecraft:stone"},transformation:[${source.matrices.initial.join(",")}]}` : ""),
      "data/bd/function/k/sparse/keyframe_2.mcfunction": encoder.encode("say first"),
      "data/bd/function/k/sparse/keyframe_7.mcfunction": encoder.encode(hasDisplay
        ? `data merge entity @e[tag=bd_0,limit=1] {transformation:[${source.matrices.instant.join(",")}]}` : "say last"),
    });
    const document = await importTestFileBatch([{ name: "sparse.zip", arrayBuffer: async () => Uint8Array.from(bytes).buffer }]);
    const [file] = await exportAnimation(document, 0);
    const animation: AnimationJson = JSON.parse(await file.blob.text());
    expect(animation.animation.duration).toBe("16t");
    expect(animation.animation.events?.timeline?.map((event) => event.time)).toEqual(hasDisplay ? ["4t"] : ["4t", "14t"]);
    expect(animation.animation.events?.timeline?.[0].action).toEqual({ type: "commands", commands: ["say first"] });
    if (!hasDisplay) {
      expect(animation.nodes).toEqual({});
      continue;
    }
    for (const tick of [0, 4, 13, 14, 15]) {
      const pose = evaluatePose(animation, tick / 20, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
      const expected = new Matrix4().set(...(tick < 14 ? source.matrices.initial : source.matrices.instant) as Parameters<Matrix4["set"]>);
      expect(pose.display_0.matrix.elements).toEqual(expected.elements);
    }
  }
});

it("preserves delayed and interrupted BD display interpolation through numeric IR curves and export", async () => {
  const matrices = source.matrices as Record<string, number[]>;
  const encoder = new TextEncoder();
  const files: Record<string, Uint8Array> = {
    "pack.mcmeta": encoder.encode('{"pack":{"description":"BD curves","pack_format":48}}'),
    "data/bd/function/_/create.mcfunction": encoder.encode("# Project created via BDEngine\n" + [0, 1].map((id) => `summon minecraft:block_display ~ ~ ~ {id:"minecraft:block_display",Tags:["bd_${id}"],block_state:{Name:"minecraft:stone"},transformation:[${matrices.initial.join(",")}]}`).join("\n")),
  };
  for (let tick = 0; tick < source.duration; tick += 2) {
    const lines = source.updates.filter((frame) => frame.tick === tick).map((frame) => `data merge entity @e[tag=bd_0,limit=1] {transformation:[${matrices[frame.target].join(",")}],interpolation_duration:${frame.duration},start_interpolation:${frame.delay}}`);
    lines.push(...source.step_updates.filter((frame) => frame.tick === tick).map((frame) => `data merge entity @e[tag=bd_1,limit=1] {transformation:[${matrices[frame.target].join(",")}],interpolation_duration:0,start_interpolation:0}`));
    files[`data/bd/function/k/curves/keyframe_${tick / 2}.mcfunction`] = encoder.encode(lines.join("\n"));
  }
  const project = await readInput("bd_datapack", { name: "curves.zip", bytes: zipSync(files) });
  expect(project.diagnostics).toEqual([]);
  const [output] = await exportAnimation(createTestDocument(project, "BD"), 0);
  const animation: AnimationJson = JSON.parse(await output.blob.text());
  expect(animation).toEqual(JSON.parse(await readFile(new URL("../../../src/test/resources/bd-curves.json", import.meta.url), "utf8")));
  expect(animation.schema_version).toBe(5);
  expect(animation.nodes.display_0.transform!.map((operation) => operation.id)).toEqual(["position", "left_rotation", "scale", "right_rotation"]);
  expect(animation.animation.tracks.map((track) => track.driver.type === "curve" ? track.driver.segments.map((segment) => segment.interpolation) : [])
    .flat().every((interpolation) => ["step", "linear", "slerp"].includes(interpolation))).toBe(true);
  const blend = (from: number[], to: number[], progress: number) => from.map((value, axis) => value + (to[axis] - value) * Math.max(0, Math.min(1, progress)));
  for (const tick of source.ticks) {
    const pose = evaluatePose(animation, tick / 20, (value) => {
      if (typeof value !== "number") throw new Error("BD curves must remain numeric.");
      return value;
    });
    const expectedPosition = tick < 6 ? blend([0, 0, 0], [8, 4, -2], (tick - 2) / 8)
      : tick < 12 ? blend([4, 2, -1], [-4, 8, 6], (tick - 8) / 6)
        : tick < 14 ? [2, -3, 5] : blend([2, -3, 5], [8, 4, -2], (tick - 12) / 8);
    for (const [axis, value] of expectedPosition.entries()) expect(pose.display_0.matrix.elements[12 + axis], `position@${tick}`).toBeCloseTo(value, 10);
    const step = tick < 4 ? matrices.initial : tick < 18 ? matrices.instant : matrices.shear;
    const expectedStep = new Matrix4().set(...step as Parameters<Matrix4["set"]>);
    for (const [axis, value] of expectedStep.elements.entries()) expect(pose.display_1.matrix.elements[axis], `matrix@${tick}`).toBeCloseTo(value, 10);
    if (tick === 12 || tick >= 20) {
      const target = new Matrix4().set(...(tick === 12 ? matrices.instant : matrices.first) as Parameters<Matrix4["set"]>);
      for (const [axis, value] of target.elements.entries()) expect(pose.display_0.matrix.elements[axis], `target@${tick}`).toBeCloseTo(value, 8);
    }
  }
});
