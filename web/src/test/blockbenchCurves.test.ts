import { readFile } from "node:fs/promises";
import { Euler, Matrix4 } from "three";
import MolangParser from "molangjs/dist/molang.esm.js";
import { expect, it } from "vitest";
import type { AnimationIR, CurveIR } from "../domain/animationIR";
import { evaluateEasing, sampleCurve, type ScalarEvaluatorIR } from "../domain/animationIRCurves";
import { evaluatePose } from "../domain/animationIRPose";
import type { ImportedNode } from "../domain/conversionSeed";
import { createGeckoLibAnimationIR } from "../import/geckoLib/geckoLibAnimationIR";
import { createAnimatedJavaAnimationIR } from "../import/animatedJava/animatedJavaAnimationIR";
import type { AjProject } from "../import/animatedJava/animatedJavaProjectSchema";
import type { BbKeyframe } from "../import/common/blockbenchCubeSchema";
import { blockbenchCurveIR } from "../import/common/blockbenchAnimationIR";
import easingReference from "./fixtures/blockbench-easings.json";

function importCurves(frames: BbKeyframe[], blend: number | string = 1): { bb: AnimationIR; aj: AnimationIR } {
  const group = { uuid: "bone", name: "Bone", origin: [16, 32, 48], rotation: [13, -17, 29] };
  const animation = { name: "curves", length: 2, loop: "once", blend_weight: blend, animators: { bone: { keyframes: frames }, item: { keyframes: frames }, block: { keyframes: frames }, text: { keyframes: frames } } };
  const imported: Record<string, ImportedNode> = {
    item: { type: "item_display", binding: { sourceNodeId: "item" }, visible: true, itemStack: { id: "minecraft:paper" }, itemDisplay: "fixed" },
    block: { type: "block_display", binding: { sourceNodeId: "block" }, visible: true, blockState: { id: "minecraft:stone" } },
    text: { type: "text_display", binding: { sourceNodeId: "text" }, visible: true, text: "test" },
  };
  const project: AjProject = { meta: { format: "animated_java_blueprint", format_version: "1" }, resolution: { width: 16, height: 16 }, groups: [group], outliner: [], textures: [], animations: [animation],
    elements: ["item", "block", "text"].map((uuid) => ({ uuid, name: uuid, type: `animated_java:vanilla_${uuid}_display`, position: [32, 64, 96], rotation: [7, 11, 19], scale: [2, 3, 4], visibility: true })),
  };
  const bb = createGeckoLibAnimationIR({ animation, animationIndex: 0, animators: new Map([["bone", animation.animators.bone]]), blendWeight: blend, playbackMode: "once", events: [] },
    [{ id: "bone", uuid: "bone", group, cubes: [], locators: [], nodes: [] }], {});
  const aj = createAnimatedJavaAnimationIR(animation, project, { groups: new Map([["bone", group]]), groupParents: new Map(), elementParents: new Map([["item", "bone"], ["block", "bone"], ["text", "bone"]]) }, imported, 1, []);
  return { bb, aj };
}

it("converts all 32 BB and AJ easings against their original source results", async () => {
  expect(easingReference.cases).toHaveLength(32);
  const parser = new MolangParser();
  const runtimeInputs: Record<string, AnimationIR> = {};
  for (const reference of easingReference.cases) {
    const frames: BbKeyframe[] = [
      { channel: "position", time: 0, data_points: [{ x: 0, y: 0, z: 0 }] },
      { channel: "position", time: 1, easing: reference.name, data_points: [{ x: 16, y: 32, z: 48 }] },
    ];
    for (const [format, animation] of Object.entries(importCurves(frames)) as ["bb" | "aj", AnimationIR][]) {
      const driver = animation.animation.tracks.find((track) => track.target.node === "group:bone")!.driver as CurveIR;
      let runtimeDriver = driver;
      const segment = driver.segments[0];
      for (const [index, progress] of easingReference.progress.entries()) {
        expect(evaluateEasing(segment.easing, progress), `${format}.${reference.name}@${progress}`).toBeCloseTo(reference[format][index], 12);
      }
      if (/Back|Elastic|Bounce|^step$/.test(reference.name)) {
        const arg = /Bounce/.test(reference.name) ? (format === "aj" ? 0.25 : 0.5) : reference.name === "step" ? 5 : 1;
        frames[1].easingArgs = [`t.argument = q.key_frame_lerp_time * 0 + ${arg}; return t.argument;`];
        const dynamic = importCurves(frames)[format].animation.tracks.find((track) => track.target.node === "group:bone")!.driver as CurveIR;
        runtimeDriver = dynamic;
        for (const [index, progress] of easingReference.progress.entries()) {
          const evaluate: ScalarEvaluatorIR = (value, u) => typeof value === "number" ? value : parser.parse(value.molang, { "query.key_frame_lerp_time": u });
          expect(evaluateEasing(dynamic.segments[0].easing, progress, evaluate), `${format}.${reference.name} dynamic@${progress}`).toBeCloseTo(reference[format][index], 12);
        }
        delete frames[1].easingArgs;
      }
      const input = runtimeInputs[format] ??= { id: `test:${format}_easings`, metadata: { name: `${format} easings`, description: "", author: "test", license: "Apache-2.0" }, nodes: { scene: animation.nodes.scene }, animation: { duration: "2s", tracks: [] } };
      input.nodes[reference.name] = animation.nodes["group:bone"];
      input.animation.tracks.push({ target: { node: reference.name, operation: "position" }, channel: "value", driver: runtimeDriver });
    }
  }
  for (const [format, input] of Object.entries(runtimeInputs)) {
    const expected = JSON.parse(await readFile(new URL(`../../../src/test/resources/${format}-easings.json`, import.meta.url), "utf8"));
    expect({ ...input, type: "animation", schema_version: 5 }).toEqual(expected);
  }
});

it("keeps independent coordinates, source channel order and one blend evaluation per node", () => {
  const frames: BbKeyframe[] = ["position", "rotation", "scale"].flatMap((channel) => [
    { channel, time: 0, data_points: [{ x: channel === "scale" ? 1 : 0, y: channel === "scale" ? 1 : 0, z: channel === "scale" ? 1 : 0 }] },
    { channel, time: 1, data_points: [{ x: channel === "scale" ? 3 : 16, y: channel === "scale" ? 5 : 32, z: channel === "scale" ? 7 : 48 }] },
  ]);
  const parser = new MolangParser();
  const raw = importCurves(frames, 0.5);
  const dynamicFrames = frames.map((frame) => ({ ...frame, data_points: frame.data_points.map((point) => Object.fromEntries(Object.entries(point).map(([axis, value]) => [axis, `t.component = ${value}; return t.component;`]))) }));
  const dynamic = importCurves(dynamicFrames, "t.weight = q.scoreboard('blend') + math.random(0, 0); return t.weight;");
  for (const format of ["bb", "aj"] as const) {
    let calls = 0;
    const evaluate: ScalarEvaluatorIR = (value, u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
      "query.key_frame_lerp_time": u, "query.value": resolveValue, "query.scoreboard": () => { calls++; return 0.5; },
    });
    const pose = evaluatePose(dynamic[format], 0.5, evaluate);
    expect(calls).toBe(format === "bb" ? 1 : 4);
    const expectedPose = evaluatePose(raw[format], 0.5, evaluate);
    for (const id of Object.keys(pose)) for (const [axis, value] of expectedPose[id].matrix.elements.entries()) expect(pose[id].matrix.elements[axis]).toBeCloseTo(value, 10);
    const groupMatrix = new Matrix4().makeTranslation(1.25, 2.5, 3.75)
      .multiply(new Matrix4().makeRotationFromEuler(new Euler(17 * Math.PI / 180, -9 * Math.PI / 180, 41 * Math.PI / 180, "ZYX")))
      .multiply(new Matrix4().makeScale(1.5, 2, 2.5));
    if (format === "bb") groupMatrix.premultiply(new Matrix4().makeScale(0.9375, 0.9375, 0.9375));
    for (const [axis, value] of groupMatrix.elements.entries()) expect(pose["group:bone"].matrix.elements[axis]).toBeCloseTo(value, 10);
    expect(dynamic[format].animation.tracks.filter((track) => track.target.node === "group:bone").map((track) => track.target.operation)).toEqual(["rotation", "position", "scale"]);
    if (format === "aj") for (const node of ["item", "block", "text"]) {
      const local = new Matrix4().makeTranslation(0.75, 2.5, 3.75)
        .multiply(new Matrix4().makeRotationFromEuler(new Euler(3 * Math.PI / 180, 3 * Math.PI / 180, 31 * Math.PI / 180, "XYZ")))
        .multiply(new Matrix4().makeScale(node === "item" ? 1.5 : 3, node === "item" ? 2 : 6, node === "item" ? 2.5 : 10));
      if (node === "text") local.multiply(new Matrix4().makeRotationY(Math.PI));
      const matrix = groupMatrix.clone().multiply(local);
      for (const [axis, value] of matrix.elements.entries()) expect(pose[node].matrix.elements[axis]).toBeCloseTo(value, 10);
    }
  }
  const queryFrames: BbKeyframe[] = [{ channel: "rotation", time: 0, data_points: [{ x: "return q.target_x_rotation;", y: 0, z: 0 }] }, { channel: "rotation", time: 1, easing: "easeInBack", easingArgs: ["q.target_x_rotation / 10"], data_points: [{ x: 0, y: 0, z: 0 }] }];
  const queryCurves = importCurves(queryFrames);
  const bbCurve = queryCurves.bb.animation.tracks[0].driver as CurveIR;
  const ajCurve = queryCurves.aj.animation.tracks[0].driver as CurveIR;
  expect(JSON.stringify(bbCurve)).toContain("-(q.target_x_rotation)");
  expect(JSON.stringify(ajCurve)).not.toContain("-(q.target_x_rotation)");
  const zero = importCurves(frames, 0);
  const evaluate: ScalarEvaluatorIR = (value) => typeof value === "number" ? value : parser.parse(value.molang);
  expect(evaluatePose(zero.aj, 0.5, evaluate).item.matrix.elements).toEqual(evaluatePose(importCurves(frames, -1).aj, 0.5, evaluate).item.matrix.elements);
});

it("preserves pre/post boundaries and relative Bezier controls without resampling random endpoints", async () => {
  const fixture: AnimationIR = JSON.parse(await readFile(new URL("../../../src/test/resources/blockbench-curves.json", import.meta.url), "utf8"));
  const frames: BbKeyframe[] = [
    { channel: "position", time: 0.5, interpolation: "bezier", data_points: [{ x: 2, y: 0, z: 0 }, { x: "q.scoreboard('start') + math.random(0, 0)", y: "q.scoreboard('start_y')", z: 0 }], bezier_right_time: ["1", "0.3333333333333333", "0.3333333333333333"], bezier_right_value: ["q.scoreboard('out')", 0, 0] },
    { channel: "position", time: 1.5, data_points: [{ x: "q.scoreboard('end') + math.random(0, 0)", y: "q.scoreboard('end_y')", z: 0 }, { x: 10, y: 0, z: 0 }], bezier_left_time: ["-1", "-0.3333333333333333", "-0.3333333333333333"], bezier_left_value: ["q.scoreboard('in')", 0, 0] },
  ];
  const curve = blockbenchCurveIR(frames)!;
  expect(curve).toEqual(fixture.animation.tracks[0].driver);
  const parser = new MolangParser();
  for (const [time, expected, called] of [[0, 2, []], [0.5, 2, []], [1, 6.375, ["start", "end", "out", "in", "start_y", "end_y"]], [1.5, 8, ["end", "end_y"]], [1.55, 10, []]] as const) {
    const calls: string[] = [];
    const evaluate: ScalarEvaluatorIR = (value) => typeof value === "number" ? value : parser.parse(value.molang, { "query.scoreboard": (id: string) => { calls.push(id); return ({ start: 4, end: 8, out: 2, in: -1, start_y: 4, end_y: 8 } as Record<string, number>)[id]; } });
    const sampled = sampleCurve(curve, time, [0, 0, 0], evaluate);
    expect(sampled[0]).toBeCloseTo(expected, 10);
    expect(sampled[1]).toBeCloseTo(time === 1 ? 6 : time === 1.5 ? 8 : 0, 10);
    expect(calls).toEqual(called);
  }
});
