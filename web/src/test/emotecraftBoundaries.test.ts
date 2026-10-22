import { createTestDocument } from "./testProject";
import { readFile } from "node:fs/promises";
import MolangParser from "molangjs/dist/molang.esm.js";
import { Matrix4 } from "three";
import { expect, it } from "vitest";
import type { AnimationIR } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import { exportAnimation } from "../export/projectExporter";
import type { PalAnimation } from "../import/emotecraft/emotecraftBinary";
import { importEmotecraftFile } from "../import/emotecraft/emotecraftImporter";
import loopSource from "./fixtures/emotecraft-loop-source.json";
import fadeSource from "./fixtures/emotecraft-fade-source.json";
import capeSource from "./fixtures/emotecraft-cape-source.json";

async function exported(source: PalAnimation, name: string): Promise<AnimationIR> {
  const project = importEmotecraftFile({ animation: source, metadata: { name, badges: [] } }, `${name}.emotecraft`);
  const [file] = await exportAnimation(createTestDocument(project, "Emotecraft"), 0);
  const animation = JSON.parse(await file.blob.text());
  expect(animation).toEqual(JSON.parse(await readFile(new URL(`../../../src/test/resources/${name}.json`, import.meta.url), "utf8")));
  return animation;
}

function pose(animation: AnimationIR, tick: number, loop = 0, calls?: string[]) {
  const parser = new MolangParser();
  return evaluatePose(animation, tick / 20, (value, progress, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
    "query.anim_time": tick / 20, "query.anim_time_ticks": tick, "query.loop_count": loop,
    "query.key_frame_lerp_time": progress, "query.value": resolveValue,
    "query.scoreboard": (id: string) => { calls?.push(id); return 0; },
  }));
}

it("preserves the PAL seam on both sides of the wrap and its easing selection", async () => {
  const source = structuredClone(loopSource as unknown as PalAnimation);
  const animation = await exported(source, "emotecraft-loop");
  for (const [tick, loop, x] of [[5, 0, 0], [30, 0, -0.625], [39, 0, -0.34375], [0, 1, -0.3125], [5, 1, -0.15625], [10, 1, 0]]) {
    expect(pose(animation, tick, loop).pal_body.matrix.elements[12]).toBeCloseTo(x, 10);
  }
  expect(animation.animation.values!.pal_body_position_x_loop_start.time_offset).toBe("40t");
  source.easeBeforeKeyframe = false;
  const after = await exported(source, "emotecraft-loop-after");
  expect(pose(after, 30).pal_body.matrix.elements[12]).toBeCloseTo(-5 / 6, 10);
  expect(pose(after, 0, 1).pal_body.matrix.elements[12]).toBeCloseTo(-25 / 48, 10);
  source.easeBeforeKeyframe = true;
  source.loopStartTick = 5;
  const returned = await exported(source, "emotecraft-loop-return");
  expect(pose(returned, 30).pal_body.matrix.elements[12]).toBeCloseTo(-0.5625, 10);
  expect(pose(returned, 5, 1).pal_body.matrix.elements[12]).toBeCloseTo(-0.1875, 10);
  expect(returned.animation.playback?.loop_start).toBe("5t");
});

it("keeps GeckoLib hold behavior and samples dynamic seam endpoints with the wrapped progress", async () => {
  const source = structuredClone(loopSource as unknown as PalAnimation);
  source.format = "geckolib";
  const gecko = await exported(source, "emotecraft-loop-gecko");
  expect(pose(gecko, 30).pal_body.matrix.elements[12]).toBeCloseTo(-0.9375, 10);
  expect(pose(gecko, 5, 1).pal_body.matrix.elements[12]).toBe(0);
  source.format = "player_animator";
  source.bones.body.position[0][1].end = "16 + global.key_frame_lerp_time * 8";
  const dynamic = await exported(source, "emotecraft-loop-dynamic");
  expect(pose(dynamic, 30).pal_body.matrix.elements[12]).toBeCloseTo(-35 / 48, 10);
  expect(pose(dynamic, 0, 1).pal_body.matrix.elements[12]).toBeCloseTo(-5 / 12, 10);
});

it("uses each channel's first-frame length and target while beginTick only gates the transition", async () => {
  const source = structuredClone(fadeSource as unknown as PalAnimation);
  const animation = await exported(source, "emotecraft-fade");
  const weight = (1 - Math.cos(Math.PI / 4)) / 2;
  const expected = new Matrix4().makeScale(0.9375, 0.9375, 0.9375).multiply(new Matrix4().makeTranslation(-weight, 1.25, 0))
    .multiply(new Matrix4().makeRotationX(-Math.PI / 2 * weight)).multiply(new Matrix4().makeScale(1 + 2 * weight, 1, 1));
  const actual = pose(animation, 5).pal_body.matrix;
  for (const [index, value] of expected.elements.entries()) expect(actual.elements[index]).toBeCloseTo(value, 10);
  expect(pose(animation, 10).pal_body.matrix.elements[12]).toBeCloseTo(-0.46875, 10);
  expect(pose(animation, 35).pal_body.matrix.elements[12]).toBeCloseTo(-0.46875, 10);
  source.bones.body.position[0][0].start = "q.scoreboard('start')";
  source.bones.body.position[0][0].end = "16 + global.key_frame_lerp_time * 8 + q.scoreboard('end')";
  const dynamic = await exported(source, "emotecraft-fade-dynamic");
  const calls: string[] = [];
  expect(pose(dynamic, 5, 0, calls).pal_body.matrix.elements[12]).toBeCloseTo(-18 / 16 * weight * 0.9375, 10);
  expect(calls).toEqual(["start", "end"]);
});

it("uses per-channel final easing with PAL's Bezier and Catmull fallback and default easing arguments", async () => {
  const source = structuredClone(fadeSource as unknown as PalAnimation);
  source.beginTick = undefined;
  source.easeBeforeKeyframe = false;
  source.bones.body.position[1][1].easing = "easeinquad";
  source.bones.body.rotation[0][0].easing = "bezier";
  source.bones.body.rotation[0][0].easingArgs = [[0], [-0.1], [0], [0.1]];
  source.bones.body.scale[0][0].easing = "constant";
  source.bones.torso.bend[0].easing = "catmullrom";
  source.bones.torso.bend[0].easingArgs = [[0], [0]];
  const animation = await exported(source, "emotecraft-fade-after");
  const weight = (1 + Math.cos(Math.PI / 5)) / 2;
  const body = new Matrix4().makeScale(0.9375, 0.9375, 0.9375).multiply(new Matrix4().makeTranslation(-0.8, 0.75 + 0.96, 0))
    .multiply(new Matrix4().makeRotationX(-Math.PI / 2 * weight)).multiply(new Matrix4().makeScale(3, 1, 1));
  const bend = Math.PI / 3 * weight;
  const lower = body.clone().multiply(new Matrix4().makeTranslation(0, 0.75, 0))
    .multiply(new Matrix4().makeTranslation(0, -0.375 + 0.125 * (1 - Math.cos(bend)), 0.125 * Math.sin(bend)))
    .multiply(new Matrix4().makeRotationX(-bend));
  const actual = pose(animation, 32);
  for (const [id, expected] of [["pal_body", body], ["pal_torso_lower", lower]] as const) {
    for (const [index, value] of expected.elements.entries()) expect(actual[id].matrix.elements[index]).toBeCloseTo(value, 10);
  }
  source.bones.body.position[0][0].easing = "step";
  source.bones.body.position[0][0].easingArgs = [[5]];
  const steps = await exported(source, "emotecraft-fade-steps");
  expect(pose(steps, 32).pal_body.matrix.elements[12]).toBeCloseTo(-0.9375, 10);
  expect(pose(steps, 40).pal_body.matrix.elements[12]).toBeCloseTo(-0.46875, 10);
});

it("omits end fade for loop and hold modes, retaining the PAL hold seam", async () => {
  const source = structuredClone(fadeSource as unknown as PalAnimation);
  source.beginTick = undefined;
  source.loop = "loop_from_tick";
  const loop = await exported(source, "emotecraft-fade-loop");
  expect(pose(loop, 32).pal_body.matrix.elements[12]).toBeCloseTo(-0.9375, 10);
  expect(Object.keys(loop.animation.values!).some((id) => id.endsWith("_fade_out"))).toBe(false);
  const heldSource = { ...structuredClone(loopSource as unknown as PalAnimation), loop: "hold" as const, endTick: 30 };
  const hold = await exported(heldSource, "emotecraft-loop-hold");
  expect(pose(hold, 30).pal_body.matrix.elements[12]).toBeCloseTo(-0.625, 10);
  expect(pose(hold, 40).pal_body.matrix.elements[12]).toBeCloseTo(-0.3125, 10);
});

it("inherits torso bending for the logical cape and its effect origin only when enabled", async () => {
  const source = structuredClone(capeSource as unknown as PalAnimation);
  const animation = await exported(source, "emotecraft-cape");
  const expected = new Matrix4().makeScale(0.9375, 0.9375, 0.9375).multiply(new Matrix4().makeTranslation(0, 18 / 16, 0))
    .multiply(new Matrix4().makeRotationX(-Math.PI / 3)).multiply(new Matrix4().makeTranslation(0, 6 / 16, 2 / 16));
  const actual = pose(animation, 10).pal_cape.matrix;
  for (const [index, value] of expected.elements.entries()) expect(actual.elements[index]).toBeCloseTo(value, 10);
  expect(animation.nodes.pal_cape.parent).toBe("inherited_torso_bend");
  expect(animation.nodes.pal_cape.attachments).toBeUndefined();
  expect(animation.animation.events!.timeline![0].origin).toEqual({ type: "node", node: "pal_cape" });
  source.applyBendToOtherBones = false;
  const disabled = await exported(source, "emotecraft-cape-off");
  expect(disabled.nodes.pal_cape.parent).toBe("pal_body");
  expect(pose(disabled, 10).pal_cape.matrix.elements[13]).toBeCloseTo(24 / 16 * 0.9375, 10);
  expect(pose(disabled, 10).pal_cape.matrix.elements[14]).toBeCloseTo(2 / 16 * 0.9375, 10);
});
