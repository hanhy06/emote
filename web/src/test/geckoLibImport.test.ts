import { expect, it } from "vitest";
import MolangParser from "molangjs/dist/molang.esm.js";
import { Euler, Matrix4, Vector3 } from "three";
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

it("anchors events to animated bone pivots independently of cube order and resolves unique locators before bone names", async () => {
  const groups: BbGroup[] = [
    { ...group("parent", "Parent"), origin: [16, 32, 48], rotation: [0, 0, 90] },
    { ...group("child", "Child"), origin: [32, 48, 48], rotation: [0, 30, 0] },
    { ...group("empty", "Empty"), origin: [16, 0, 0] }, group("tip_bone", "Tip"),
  ];
  const cubes = [
    { uuid: "rotated", type: "cube", from: [32, 48, 48], to: [34, 50, 50], origin: [80, 48, 48], rotation: [0, 45, 0], faces: {} },
    { uuid: "other", type: "cube", from: [36, 48, 48], to: [38, 50, 50], faces: {} },
  ];
  const locator = { uuid: "locator", type: "locator" as const, name: "Tip", position: [48, 64, 48], rotation: [10, 0, 20] };
  const animation: Partial<BbAnimation> = { animators: {
    child: { keyframes: [
      { channel: "rotation", time: 0, data_points: [{ x: 0, y: 0, z: 0 }] },
      { channel: "rotation", time: 1, data_points: [{ x: 0, y: 20, z: 0 }] },
    ] },
    effects: { type: "effect", keyframes: [
      { channel: "particle", time: 0, data_points: [{ effect: "minecraft:flame", locator: "Child" }] },
      { channel: "sound", time: 0.25, data_points: [{ effect: "minecraft:block.note_block.harp", locator: "Tip" }] },
      { channel: "timeline", time: 0.5, data_points: [{ script: "/say retained", locator: "Empty" }] },
    ] },
  } };
  for (const contents of [cubes, [...cubes].reverse(), []]) {
    const { input, output } = await importAnimation(groups, animation, {
      elements: [...contents, locator], outliner: [
        { uuid: "parent", children: [{ uuid: "child", children: [...contents.map((cube) => cube.uuid), locator.uuid] }] },
        { uuid: "empty", children: [] }, { uuid: "tip_bone", children: [] },
      ],
    });
    expect(input.diagnostics).toEqual([]);
    const events = output.animation.events!.timeline!;
    expect(events.map((event) => event.origin)).toEqual([
      { type: "node", node: "group:child" }, { type: "node", node: "child_tip" }, { type: "node", node: "group:empty" },
    ]);
    expect(events.map((event) => event.action)).toEqual([
      { type: "commands", commands: ["particle minecraft:flame ~ ~ ~"] },
      { type: "commands", commands: ["playsound minecraft:block.note_block.harp master @s ~ ~ ~"] },
      { type: "commands", commands: ["say retained"] },
    ]);
    for (const time of [0, 0.5, 1]) {
      const pose = evaluatePose(output, time, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
      const expected = new Matrix4().makeScale(0.9375, 0.9375, 0.9375)
        .multiply(new Matrix4().makeTranslation(1, 2, 3)).multiply(new Matrix4().makeRotationZ(Math.PI / 2))
        .multiply(new Matrix4().makeTranslation(1, 1, 0)).multiply(new Matrix4().makeRotationY((30 + 20 * time) * Math.PI / 180));
      const expectedLocator = expected.clone().multiply(new Matrix4().makeTranslation(1, 1, 0))
        .multiply(new Matrix4().makeRotationFromEuler(new Euler(10 * Math.PI / 180, 0, 20 * Math.PI / 180, "ZYX")));
      for (const [id, matrix] of [["group:child", expected], ["child_tip", expectedLocator]] as const) {
        const actualPoint = new Vector3().applyMatrix4(pose[id].matrix);
        expect(actualPoint.distanceTo(new Vector3().applyMatrix4(matrix))).toBeLessThan(1e-10);
      }
      expect(new Vector3().applyMatrix4(pose["group:empty"].matrix).toArray()).toEqual([0.9375, 0, 0]);
    }
  }
  const unresolved = await importAnimation([group("a", "Same"), group("b", "Same"), group("c", "Shared")], { animators: {
    effects: { type: "effect", keyframes: ["Same", "Shared", "Missing", ""].map((name) => ({ channel: "timeline", time: 0, data_points: [{ script: "/say private", locator: name }] })) },
  } }, {
    elements: ["a", "b"].map((id) => ({ ...locator, uuid: `${id}_locator`, name: "Shared" })),
    outliner: [{ uuid: "a", children: ["a_locator"] }, { uuid: "b", children: ["b_locator"] }, { uuid: "c", children: [] }],
  });
  expect(unresolved.output.animation.events!.timeline!.every((event) => event.origin.type === "root")).toBe(true);
  expect(unresolved.input.diagnostics.map((issue) => issue.code)).toEqual(Array(3).fill("geckolib_effect_origin_approximated"));
  expect(JSON.stringify(unresolved.input.diagnostics)).not.toContain("say private");
});
