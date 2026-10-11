import { readFile } from "node:fs/promises";
import { Euler, Matrix4 } from "three";
import MolangParser from "molangjs/dist/molang.esm.js";
import { expect, it } from "vitest";
import type { AnimationIR } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import type { ScalarEvaluatorIR } from "../domain/animationIRCurves";
import { createConversionDocument } from "../domain/conversionDocument";
import { exportAnimation } from "../export/projectExporter";
import { readInput } from "../import/formats";
import { createBedrockAnimationIR } from "../import/bedrockAnimation/bedrockAnimationIR";
import type { BedrockAnimationDocument } from "../import/bedrockAnimation/bedrockAnimationSchema";
import source from "./fixtures/bedrock-curves.json";

it("preserves Bedrock coordinates, pre/post, Catmull boundaries and playback through export", async () => {
  const bytes = new TextEncoder().encode(JSON.stringify(source));
  const project = await readInput("bedrock_animation_json", { name: "curves.animation.json", bytes });
  const document = createConversionDocument(project, "Bedrock");
  for (const [index, name] of ["reference", "dynamic"].entries()) {
    const [output] = await exportAnimation(document, index);
    const exported = JSON.parse(await output.blob.text());
    expect(exported).toEqual(JSON.parse(await readFile(new URL(`../../../src/test/resources/bedrock-${name}.json`, import.meta.url), "utf8")));
    expect(exported.schema_version).toBe(5);
  }
  const animation = project.animations[0].ir;
  const parser = new MolangParser();
  const evaluate: ScalarEvaluatorIR = (value, u) => typeof value === "number" ? value : parser.parse(value.molang, { "query.key_frame_lerp_time": u });
  for (const [time, rootX, propX] of [[0, 0, 0], [0.25, 0, -0.046875], [0.5, -0.5, -0.5], [1, -0.75, -0.96875], [1.5, -2, -2], [1.75, -2, -2.34375], [2, -2, -2.5]]) {
    const pose = evaluatePose(animation, time, evaluate);
    const root = new Matrix4().makeTranslation(rootX, -2 * rootX, -3 * rootX)
      .multiply(new Matrix4().makeRotationFromEuler(new Euler(-10 * Math.PI / 180, -20 * Math.PI / 180, 30 * Math.PI / 180, "ZYX")))
      .multiply(new Matrix4().makeScale(1.5, 1.5, 1.5)).premultiply(new Matrix4().makeScale(0.9375, 0.9375, 0.9375));
    for (const [axis, value] of root.elements.entries()) expect(pose["bone:root"].matrix.elements[axis], `root@${time}`).toBeCloseTo(value, 10);
    const prop = new Matrix4().makeTranslation(propX, -2 * propX, -3 * propX).premultiply(new Matrix4().makeScale(0.9375, 0.9375, 0.9375));
    for (const [axis, value] of prop.elements.entries()) expect(pose["bone:bedrock_custom_prop"].matrix.elements[axis], `prop@${time}`).toBeCloseTo(value, 10);
  }
  expect(animation.nodes["bone:head"].inherit).toEqual({ rotation: "entity" });
  expect(animation.animation.clock).toEqual({ type: "molang", expression: source.animations.reference.anim_time_update });
  expect(animation.animation.playback).toEqual({ mode: "hold", start_delay: "3t", loop_delay: { molang: "q.is_sneaking * 0.1" } });
  expect(animation.animation.events?.timeline).toEqual([{ time: "0.75s", source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: ["say bedrock curve"] } }]);
});

it("samples numeric and Molang channels identically with one shared blend and no endpoint deduplication", () => {
  const animation = createBedrockAnimationIR((source as BedrockAnimationDocument).animations.dynamic, "dynamic", {}, { prop: "bedrock_custom_prop" }, []);
  const parser = new MolangParser();
  for (const time of [0, 0.25, 0.5, 1, 1.5, 2]) {
    const calls: string[] = [];
    const evaluate: ScalarEvaluatorIR = (value, u, _base, resolveValue) => typeof value === "number" ? value : parser.parse(value.molang, {
      "query.key_frame_lerp_time": u, "query.value": resolveValue,
      "query.scoreboard": (id: string) => {
        calls.push(id);
        if (id === "blend") return 0.5;
        if (id === "angle") return calls.filter((called) => called === id).length * 10;
        if (id === "scale") return calls.filter((called) => called === id).length + 1;
        const axis = id.endsWith("_x") ? 1 : id.endsWith("_y") ? 2 : 3;
        return (id.startsWith("cat_start") ? 0 : id.startsWith("post") ? 64 : id.startsWith("end") ? 32 : 16) * axis;
      },
    });
    const pose = evaluatePose(animation, time, evaluate);
    const positionCalls = time < 0.5 ? [] : time < 1.5 ? time === 0.5 ? ["start_x", "start_y", "start_z"] : ["start_x", "start_y", "start_z", "end_x", "end_y", "end_z"] : ["post_x", "post_y", "post_z"];
    const catCalls = time === 0 ? ["cat_start_x", "cat_start_y", "cat_start_z"] : time < 1 ? ["cat_start_x", "cat_start_y", "cat_start_z", "cat_end_x", "cat_end_y", "cat_end_z"] : ["cat_end_x", "cat_end_y", "cat_end_z"];
    expect(calls).toEqual(["blend", ...positionCalls, "angle", "angle", "angle", "scale", "scale", "scale", ...catCalls]);
    const raw = structuredClone((source as BedrockAnimationDocument).animations.dynamic);
    raw.blend_weight = 0.5;
    raw.bones!.root.rotation = [10, 20, 30];
    raw.bones!.root.scale = [2, 3, 4];
    raw.bones!.root.position = { "0.5": { pre: [99, 99, 99], post: [16, 32, 48] }, "1.5": { pre: [32, 64, 96], post: [64, 128, 192] } };
    raw.bones!.prop.position = { "0.0": [0, 0, 0], "1.0": { post: [16, 32, 48], lerp_mode: "catmullrom" } };
    const numeric = evaluatePose(createBedrockAnimationIR(raw, "numeric", {}, { prop: "bedrock_custom_prop" }, []), time, evaluate);
    for (const id of Object.keys(pose)) for (const [axis, value] of numeric[id].matrix.elements.entries()) expect(pose[id].matrix.elements[axis], `${id}@${time}`).toBeCloseTo(value, 10);
  }
  const tracks = animation.animation.tracks;
  expect(tracks.filter((track) => track.target.node === "bone:root").map((track) => track.target.operation)).toEqual(["position", "rotation", "scale"]);
  expect(Object.keys(animation.animation.values!)).toEqual(["blend"]);
  expect(tracks.every((track) => track.blend?.weight && JSON.stringify(track.blend.weight) === JSON.stringify({ molang: "q.value('blend', 0)" }))).toBe(true);
});
