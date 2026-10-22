import { createTestDocument } from "./testProject";
import { readFile } from "node:fs/promises";
import { Euler, Matrix4 } from "three";
import MolangParser from "molangjs/dist/molang.esm.js";
import { expect, it } from "vitest";
import type { AnimationIR, CurveIR } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import type { ScalarEvaluatorIR } from "../domain/animationIRCurves";
import { exportAnimation } from "../export/projectExporter";
import { readInput } from "../import/formats";
import { createEmotecraftAnimationIR } from "../import/emotecraft/emotecraftAnimationIR";
import { importEmotecraftFile } from "../import/emotecraft/emotecraftImporter";
import type { PalAnimation, PalBoneAnimation, PalKeyframe } from "../import/emotecraft/emotecraftBinary";
import source from "./fixtures/emotecraft-curves.json";
import easings from "./fixtures/blockbench-easings.json";

const reference = source as unknown as PalAnimation;
const dynamic = structuredClone(reference);
for (const [name, bone] of Object.entries(dynamic.bones)) for (const channel of ["rotation", "position", "scale", "bend"] as const) {
  const axes = channel === "bend" ? [bone.bend] : bone[channel];
  for (const [axis, frames] of axes.entries()) for (const [index, frame] of frames.entries()) {
    for (const field of ["start", "end"] as const) {
      const raw = Number(frame[field]) * (channel === "rotation" || channel === "bend" ? 180 / Math.PI : 1);
      frame[field] = `t.value = q.scoreboard('${name}.${channel}.${axis}.${index}.${field}:${raw}') + math.random(0, 0) + q.key_frame_lerp_time * 0; return t.value;`;
    }
    frame.easingArgs = frame.easingArgs.map((args, argument) => [`t.argument = q.scoreboard('${name}.${channel}.${axis}.${index}.arg${argument}:${args[0]}')`, "return t.argument"]);
  }
}

it("samples all PAL easings through scalar curves with dynamic arguments and segment key boundaries", () => {
  const parser = new MolangParser();
  const evaluate: ScalarEvaluatorIR = (value, u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, { "query.key_frame_lerp_time": u, "query.value": resolveValue });
  for (const easing of easings.cases) for (const expression of [false, true]) {
    const frame: PalKeyframe = { startTick: 0, endTick: 20, start: expression ? "t.a = 0; return t.a;" : 0, end: expression ? "16 + global.key_frame_lerp_time * 0" : 16, easing: easing.name.toLowerCase(), easingArgs: [] };
    if (expression && /back|elastic|bounce|^step$/.test(frame.easing)) {
      const value = /bounce/.test(frame.easing) ? 0.5 : frame.easing === "step" ? 2 : 1;
      frame.easingArgs = [[`t.argument = ${value}`, "return t.argument"]];
    }
    const bone: PalBoneAnimation = { position: [[frame], [], []], rotation: [[], [], []], scale: [[], [], []], bend: [] };
    const { ir } = createEmotecraftAnimationIR({ ...reference, bones: { body: bone }, parents: {}, applyBendToOtherBones: false }, "easing", []);
    expect(ir.animation.values!.pal_body_position_x.type).toBe("curve");
    for (const [index, progress] of easings.progress.entries()) {
      const expected = progress === 1 ? 1 : frame.easing === "step" ? progress > 0.5 ? 0.5 : 0 : easing.bb[index];
      expect(evaluatePose(ir, progress, evaluate).pal_body.matrix.elements[12] / -0.9375, `${frame.easing}@${progress}`).toBeCloseTo(expected, 10);
    }
  }
  const bone: PalBoneAnimation = { position: [[{ startTick: 0, endTick: 10, start: 0, end: 8, easing: "constant", easingArgs: [] }, { startTick: 10, endTick: 20, start: 8, end: 16, easing: "easeinexpo", easingArgs: [] }], [], []], rotation: [[], [], []], scale: [[], [], []], bend: [] };
  const { ir } = createEmotecraftAnimationIR({ ...reference, bones: { body: bone }, parents: {}, applyBendToOtherBones: false }, "boundary", []);
  expect(evaluatePose(ir, 0.25, evaluate).pal_body.matrix.elements[12]).toBe(0);
  expect(evaluatePose(ir, 0.5, evaluate).pal_body.matrix.elements[12]).toBeCloseTo(-(8 + 8 / 1024) / 16 * 0.9375, 12);
  const bezier: PalBoneAnimation = { ...bone, position: [[{ startTick: 0, endTick: 20, start: 0, end: 16, easing: "bezier", easingArgs: [[4], [-0.1], ["q.scoreboard('unused')"]] }], [], []] };
  const shortControls = createEmotecraftAnimationIR({ ...reference, bones: { body: bezier }, parents: {}, applyBendToOtherBones: false }, "short-controls", []).ir;
  expect(evaluatePose(shortControls, 0.5, evaluate).pal_body.matrix.elements[12]).toBeCloseTo(-9.5 / 16 * 0.9375, 12);
  const progressBone: PalBoneAnimation = { ...bone, position: [[{ startTick: 0, endTick: 20, start: 0, end: "global.key_frame_lerp_time * 16", easing: "linear", easingArgs: [] }], [], []] };
  const progressCurve = createEmotecraftAnimationIR({ ...reference, bones: { body: progressBone }, parents: {}, applyBendToOtherBones: false }, "progress", []).ir;
  expect(evaluatePose(progressCurve, 0.5, evaluate).pal_body.matrix.elements[12]).toBeCloseTo(-0.25 * 0.9375, 12);
});

it("preserves independent axes and reuses one bend sample for joint translation, rotation and inherited bones", async () => {
  const parser = new MolangParser();
  for (const [name, animation] of [["reference", reference], ["dynamic", dynamic]] as const) {
    const project = importEmotecraftFile({ animation, metadata: { name, badges: [] } }, `${name}.emotecraft`);
    const [output] = await exportAnimation(createTestDocument(project, "Emotecraft"), 0);
    const exported: AnimationIR = JSON.parse(await output.blob.text());
    expect(exported).toEqual(JSON.parse(await readFile(new URL(`../../../src/test/resources/emotecraft-${name}.json`, import.meta.url), "utf8")));
    for (const tick of [0, 5, 10, 20, 40]) {
      const calls: string[] = [];
      const evaluate: ScalarEvaluatorIR = (value, u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
        "query.key_frame_lerp_time": u, "query.value": resolveValue, "query.scoreboard": (id: string) => { calls.push(id); return Number(id.slice(id.lastIndexOf(":") + 1)); },
      });
      const pose = evaluatePose(exported, tick / 20, evaluate);
      const slow = tick / 40, fast = Math.min(1, tick / 20), fastest = Math.min(1, tick / 10), bend = 60 * fast * Math.PI / 180;
      const body = new Matrix4().makeScale(0.9375, 0.9375, 0.9375).multiply(new Matrix4().makeTranslation(-slow, 0.75, 0))
        .multiply(new Matrix4().makeRotationFromEuler(new Euler(-60 * fast * Math.PI / 180, -30 * slow * Math.PI / 180, 20 * fastest * Math.PI / 180, "ZYX")))
        .multiply(new Matrix4().makeScale(1 + fast, 1 + 2 * fast, 1 + 3 * fast));
      const lower = body.clone().multiply(new Matrix4().makeTranslation(0, 0.75, 0))
        .multiply(new Matrix4().makeTranslation(0, -0.375 + 0.125 * (1 - Math.cos(bend)), 0.125 * Math.sin(bend)))
        .multiply(new Matrix4().makeRotationX(-bend));
      for (const [id, expected] of [["pal_body", body], ["pal_torso_lower", lower]] as const) for (const [axis, value] of expected.elements.entries()) expect(pose[id].matrix.elements[axis], `${name} ${id}@${tick}`).toBeCloseTo(value, 10);
      if (tick === 10) {
        const controls = body.clone().multiply(new Matrix4().makeTranslation(-10.25 / 16, -0.75, 0));
        for (const [axis, value] of controls.elements.entries()) expect(pose.pal_controls.matrix.elements[axis]).toBeCloseTo(value, 10);
        const head = body.clone().multiply(new Matrix4().makeTranslation(0, 0.375, 0)).multiply(new Matrix4().makeRotationX(-bend))
          .multiply(new Matrix4().makeTranslation(0, -0.375, 0)).multiply(new Matrix4().makeTranslation(-10.25 / 16, -0.75, 0))
          .multiply(new Matrix4().makeTranslation(0, 1.5, 0)).multiply(new Matrix4().makeRotationX(-45 * slow * Math.PI / 180));
        for (const [axis, value] of head.elements.entries()) expect(pose.pal_head.matrix.elements[axis]).toBeCloseTo(value, 10);
        const armBend = 13.125 * Math.PI / 180;
        const arm = body.clone().multiply(new Matrix4().makeTranslation(0, 0.375, 0)).multiply(new Matrix4().makeRotationX(-bend))
          .multiply(new Matrix4().makeTranslation(0, -0.375, 0)).multiply(new Matrix4().makeTranslation(5 / 16, 10 / 16, 0))
          .multiply(new Matrix4().makeTranslation(0, -0.375 + 0.125 * (1 - Math.cos(armBend)), 0.125 * Math.sin(armBend))).multiply(new Matrix4().makeRotationX(-armBend));
        for (const [axis, value] of arm.elements.entries()) expect(pose.pal_right_arm_lower.matrix.elements[axis]).toBeCloseTo(value, 10);
      }
      if (name === "dynamic") {
        expect(calls.filter((id) => id.startsWith("torso.bend"))).toHaveLength(2);
        expect(calls.filter((id) => id.startsWith("controls.position"))).toHaveLength(tick === 0 || tick >= 20 ? 2 : 6);
        expect(calls.slice(0, 6).map((id) => id.slice(0, id.indexOf(":")))).toEqual(["body.rotation.0.0.start", "body.rotation.0.0.end", "body.rotation.1.0.start", "body.rotation.1.0.end", "body.rotation.2.0.start", "body.rotation.2.0.end"]);
        if (tick === 10) expect(calls.filter((id) => id.startsWith("controls.position")).map((id) => id.slice(0, id.indexOf(":")))).toEqual(["controls.position.0.0.start", "controls.position.0.0.end", "controls.position.0.0.arg0", "controls.position.0.0.arg1", "controls.position.0.0.arg2", "controls.position.0.0.arg3"]);
      }
    }
    expect((exported.animation.values!.pal_long_position_x as CurveIR).keys.at(-1)!.time).toBe("80t");
  }
});

it("connects a binary packet to the same IR", async () => {
  const parser = new MolangParser();
  const evaluate: ScalarEvaluatorIR = (value, u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, { "query.key_frame_lerp_time": u, "query.value": resolveValue });
  const bytes = Uint8Array.from([0,0,0,8,16,1,153,6,0,0,0,47,0,0,0,0,4,65,160,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,4,98,111,100,121,1,1,1,63,201,15,219,65,160,0,0,0,0,0,0,0]);
  const project = await readInput("emotecraft_binary", { name: "binary.emotecraft", bytes });
  const pose = evaluatePose(project.animations[0].ir, 0.5, evaluate);
  const expected = new Matrix4().makeScale(0.9375, 0.9375, 0.9375).multiply(new Matrix4().makeTranslation(0, 0.75, 0)).multiply(new Matrix4().makeRotationX(-Math.PI / 4));
  for (const [axis, value] of expected.elements.entries()) expect(pose.pal_body.matrix.elements[axis]).toBeCloseTo(value, 6);
});
