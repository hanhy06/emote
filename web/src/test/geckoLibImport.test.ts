import { expect, it } from "vitest";
import MolangParser from "molangjs/dist/molang.esm.js";
import { createConversionDocument } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { evaluatePose } from "../domain/animationIRPose";
import { readInput } from "../import/formats";
import type { BbAnimation, BbGroup, BlockbenchCubeProject } from "../import/common/blockbenchCubeSchema";

async function importAnimation(groups: BbGroup[], animation: Partial<BbAnimation>, extra: Partial<BlockbenchCubeProject> = {}) {
  const project = {
    meta: { model_format: "geckolib_model" }, name: "model", resolution: { width: 64, height: 64 }, textures: [],
    elements: [], groups, outliner: groups.map((group) => ({ uuid: group.uuid, children: [] })),
    animations: [{ name: "motion", length: 1, loop: "loop", animators: {}, ...animation }], ...extra,
  };
  const input = await readInput("geckolib_bbmodel", { name: "model.bbmodel", bytes: new TextEncoder().encode(JSON.stringify(project)) });
  const document = createConversionDocument(input, "BB");
  return { input, document, output: compileAnimation(document, 0).animation };
}

const group = (uuid: string, name: string): BbGroup => ({ uuid, name, origin: [0, 0, 0], rotation: [0, 0, 0] });
const movement = (x: number) => ({ keyframes: [{ channel: "position", time: 0, data_points: [{ x, y: 0, z: 0 }] }] });

it("matches accessory and ordinary animators consistently while UUIDs take precedence over unique names", async () => {
  const groups = [group("accessory", "rightItem"), group("ordinary", "Prop"), group("duplicate_a", "Same"), group("duplicate_b", "Same")];
  const byUuid = await importAnimation(groups, { animators: { accessory: movement(16) } });
  const byName = await importAnimation(groups, { animators: { saved_id: { name: "rightItem", ...movement(16) } } });
  const evaluate = (value: number | { molang: string }) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; };
  expect(evaluatePose(byName.output, 0, evaluate)["group:accessory"].matrix.elements).toEqual(evaluatePose(byUuid.output, 0, evaluate)["group:accessory"].matrix.elements);
  expect(evaluatePose(byName.output, 0, evaluate)["group:accessory"].matrix.elements[12]).toBeCloseTo(0.9375, 12);
  expect(byName.input.diagnostics).toEqual([]);

  const mixed = await importAnimation(groups, { animators: {
    fallback_first: { name: "prop", ...movement(80) }, ordinary: { name: "wrong name", ...movement(32) },
    duplicate_a: movement(48), ambiguous: { name: "Same", ...movement(64) }, missing: { name: "Missing", ...movement(16) },
  } });
  const pose = evaluatePose(mixed.output, 0, evaluate);
  expect(pose["group:ordinary"].matrix.elements[12]).toBeCloseTo(1.875, 12);
  expect(pose["group:duplicate_a"].matrix.elements[12]).toBeCloseTo(2.8125, 12);
  expect(pose["group:duplicate_b"].matrix.elements[12]).toBe(0);
  const warnings = mixed.input.diagnostics.filter((issue) => issue.code === "geckolib_animator_ignored");
  expect(warnings.map((issue) => issue.sourcePath)).toEqual([
    "animations[0].animators.fallback_first", "animations[0].animators.ambiguous", "animations[0].animators.missing",
  ]);
  expect(warnings.map((issue) => issue.message)).toEqual([
    "motion: an animator was omitted because the bone already has an animator.",
    "motion: an animator was omitted because the bone name is ambiguous.",
    "motion: an animator was omitted because no matching bone was found.",
  ]);
});

it("corrects player rotation queries in both delays while preserving seconds, quoted identifiers and defaults", async () => {
  const { output } = await importAnimation([group("bone", "Prop")], {
    start_delay: "t.delay = 2 + q.target_x_rotation / 30 + q.scoreboard('q.target_x_rotation'); return t.delay;",
    loop_delay: "return 3 + query.target_y_rotation / 90 + q.target_x_rotation / 30;",
  });
  const playback = output.animation.playback!;
  expect(playback.start_delay).toEqual({ molang: "t.delay = 2 + -(q.target_x_rotation) / 30 + q.scoreboard('q.target_x_rotation'); return t.delay;" });
  expect(playback.loop_delay).toEqual({ molang: "return 3 + -(query.target_y_rotation) / 90 + -(q.target_x_rotation) / 30;" });
  const parser = new MolangParser();
  for (const value of [playback.start_delay, playback.loop_delay]) {
    if (!value || typeof value === "string") throw new Error("Expected preserved Molang delay");
    expect(parser.parse(value.molang, { "query.target_x_rotation": 30, "query.target_y_rotation": 90, "query.scoreboard": () => 0 })).toBeCloseTo(1, 12);
  }
  for (const values of [{}, { start_delay: "", loop_delay: "", blend_weight: "" }, { start_delay: 0, loop_delay: "0" }]) {
    const { output } = await importAnimation([group("bone", "Prop")], { ...values, animators: { bone: movement(16) } });
    expect(output.animation.playback).toEqual({ mode: "loop", start_delay: "0t", loop_delay: "0t" });
    expect(output.animation.tracks[0].blend).toBeUndefined();
  }
  const numeric = await importAnimation([group("bone", "Prop")], { start_delay: 0.15, loop_delay: "0.25" });
  expect(numeric.output.animation.playback).toEqual({ mode: "loop", start_delay: "3t", loop_delay: "5t" });
});
