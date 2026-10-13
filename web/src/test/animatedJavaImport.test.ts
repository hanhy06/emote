import { expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import { Euler, Matrix4 } from "three";
import { createConversionDocument } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { evaluatePose } from "../domain/animationIRPose";
import { readInput } from "../import/formats";
import { parseSnbtCompound } from "../format/snbt";
import { parseMinecraftTime } from "../format/time";
import { exportAnimation } from "../export/projectExporter";
import type { NbtPatchIR } from "../domain/animationIR";
import type { AjProject, AjProjectDisplayElement, AjProjectGroup } from "../import/animatedJava/animatedJavaProjectSchema";

async function importProject(extra: Partial<AjProject> = {}) {
  const project: AjProject = {
    meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
    name: "model", resolution: { width: 64, height: 64 }, textures: [], elements: [], groups: [], outliner: [],
    animations: [{ name: "motion", length: 1, loop: "once", animators: {} }], ...extra,
  };
  const input = await readInput("animated_java_blueprint", { name: "model.ajblueprint", bytes: new TextEncoder().encode(JSON.stringify(project)) });
  const document = createConversionDocument(input, "AJ");
  return { input, document, output: compileAnimation(document, 0).animation };
}

const group = (uuid: string): AjProjectGroup => ({ uuid, name: uuid, origin: [16, 32, 48], rotation: [0, 0, 0] });
const display = (uuid: string): AjProjectDisplayElement => ({
  uuid, name: uuid, type: "animated_java:vanilla_item_display", position: [32, 48, 64], rotation: [10, 20, 30],
  scale: [2, 3, 4], visibility: true, item: "minecraft:stone",
});
const nbtFields = (value: string | undefined) => Object.fromEntries(parseSnbtCompound(value ?? "{}").map((field) => [field.name, field.value]));

it("keeps AJ source size with and without cube groups and keeps direct display configs independent of their parent", async () => {
  const bone = { ...group("bone"), configs: { default: { invisible: true, billboard: "center", shadow_radius: 5 } } };
  const element = { ...display("item"), configs: { default: { invisible: false, billboard: "fixed", shadow_radius: 2 } } };
  const cube = { uuid: "cube", name: "cube", type: "cube" as const, from: [16, 32, 48], to: [18, 34, 50], faces: {} };
  const expected = new Matrix4().makeTranslation(2, 3, 4)
    .multiply(new Matrix4().makeRotationFromEuler(new Euler(10 * Math.PI / 180, 20 * Math.PI / 180, 30 * Math.PI / 180, "XYZ")))
    .multiply(new Matrix4().makeScale(2, 3, 4));
  for (const hasGroup of [false, true]) for (const hasCube of hasGroup ? [false, true] : [false]) {
    const { input, output } = await importProject({
      elements: [element, ...(hasCube ? [cube] : [])], groups: hasGroup ? [bone] : [],
      outliner: hasGroup ? [{ uuid: bone.uuid, children: [element.uuid, ...(hasCube ? [cube.uuid] : [])] }] : [element.uuid],
    });
    expect(input.diagnostics).toEqual([]);
    const pose = evaluatePose(output, 0, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
    expect(pose.item.matrix.elements.every((value, axis) => Math.abs(value - expected.elements[axis]) < 1e-10)).toBe(true);
    expect(pose.item.visible).toBe(true);
    expect(nbtFields(output.nodes.item.attachments?.display.entity_nbt)).toEqual({ billboard: '"fixed"', shadow_radius: "2" });
    if (hasCube) {
      expect(output.nodes.bone.visible).toBe(false);
      expect(nbtFields(output.nodes.bone.attachments?.display.entity_nbt)).toEqual({ billboard: '"center"', shadow_radius: "5" });
    }
  }
});

it("imports independent sky and block brightness and only applies enabled brightness and glow overrides", async () => {
  const cases = [
    { uuid: "enabled", config: { override_brightness: true, sky_brightness: 12, block_brightness: 3, glowing: true, override_glow_color: true, glow_color: "#123456" }, expected: { brightness: "{sky:12,block:3}", Glowing: "1b", glow_color_override: "1193046" } },
    { uuid: "disabled", config: { override_brightness: false, sky_brightness: 12, block_brightness: 3, brightness_override: 15, glowing: false, override_glow_color: false, glow_color: "#123456" }, expected: { Glowing: "0b" } },
    { uuid: "implicit", config: { sky_brightness: 12, block_brightness: 3, glow_color: "#123456" }, expected: {} },
    { uuid: "defaults", config: { override_brightness: true, override_glow_color: true }, expected: { brightness: "{sky:0,block:0}", glow_color_override: "16777215" } },
  ];
  const { input, output } = await importProject({ elements: cases.map(({ uuid, config }) => ({ ...display(uuid), configs: { default: config } })), outliner: cases.map(({ uuid }) => uuid) });
  expect(input.diagnostics).toEqual([]);
  for (const { uuid, expected } of cases) expect(nbtFields(output.nodes[uuid].attachments?.display.entity_nbt)).toEqual(expected);

  const direct = display("item");
  const bright = await importProject({
    elements: [{ ...direct, configs: { variants: { bright: { override_brightness: true, sky_brightness: 5, block_brightness: 2 } } } }],
    outliner: ["item"], variants: { list: [{ uuid: "bright", name: "bright" }] },
    animations: [{ name: "motion", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
      { channel: "variant", time: 0.5, data_points: [{ variant: "bright" }] },
    ] } } }],
  });
  const track = bright.output.animation.tracks.find((track) => track.channel === "nbt" && track.target.node === "item");
  expect(bright.input.diagnostics).toEqual([]);
  expect(track?.driver).toEqual({ type: "state", keys: [
    { time: "0t", value: { merge: "{}", remove: ["brightness"] } },
    { time: "10t", value: { merge: "{brightness:{sky:5,block:2}}" } },
  ] });
});

it("skips excluded variant nodes, preserves partial patches and reapplies defaults without overriding direct displays", async () => {
  const bone = { ...group("bone"), configs: {
    default: { invisible: false, billboard: "fixed", shadow_radius: 1, override_brightness: true, sky_brightness: 2, block_brightness: 3 },
    variants: {
      active_uuid: { invisible: true, billboard: "center", shadow_radius: 4, override_brightness: true, sky_brightness: 12, block_brightness: 8 },
      patch_uuid: { shadow_strength: 0.5 }, skipped_uuid: { invisible: false, shadow_radius: 99 },
    },
  } };
  const child = { ...group("child"), configs: { variants: { skipped_uuid: { invisible: true } } } };
  const item = { ...display("item"), configs: {
    default: { invisible: false, billboard: "fixed", shadow_radius: 2 },
    variants: { active_uuid: { invisible: true, shadow_radius: 9 }, patch_uuid: { shadow_strength: 0.25 }, skipped_uuid: { invisible: false, shadow_radius: 7 }, default_uuid: { invisible: false, billboard: "vertical", shadow_radius: 2 } },
  } };
  const excludedItem = { ...display("excluded"), configs: { default: { shadow_radius: 3 }, variants: { active_uuid: { invisible: true, shadow_radius: 99 } } } };
  const cubes = ["cube_a", "cube_b", "cube_child"].map((uuid) => ({ uuid, name: uuid, type: "cube" as const, from: [16, 32, 48], to: [18, 34, 50], faces: {} }));
  const variants = { default: { uuid: "default_uuid", name: "default", is_default: true }, list: [
    { uuid: "active_uuid", name: "active", excluded_nodes: ["excluded"] }, { uuid: "patch_uuid", name: "patch" },
    { uuid: "skipped_uuid", name: "skipped", excluded_nodes: ["bone"] },
  ] };
  const { input, document, output } = await importProject({
    elements: [...cubes, item, excludedItem], groups: [bone, child], variants,
    outliner: [{ uuid: "bone", children: ["cube_a", "cube_b", "item", "excluded", { uuid: "child", children: ["cube_child"] }] }],
    animations: [{ name: "motion", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
      { channel: "variant", time: 0.2, data_points: [{ variant: "active" }] },
      { channel: "variant", time: 0.4, data_points: [{ variant: "patch_uuid" }] },
      { channel: "variant", time: 0.6, data_points: [{ variant: "skipped_uuid" }] },
      { channel: "variant", time: 0.8, data_points: [{ variant: "default" }] },
    ] } } }],
  });
  expect(input.diagnostics).toEqual([]);
  const cubeNodes = Object.entries(output.nodes).filter(([, node]) => typeof node.source?.node_id === "string" && ["cube_a", "cube_b"].includes(node.source.node_id));
  expect(cubeNodes).toHaveLength(2);
  const stateAt = (id: string, tick: number) => {
    const state = nbtFields(output.nodes[id].attachments?.display.entity_nbt);
    const track = output.animation.tracks.find((track) => track.target.node === id && track.channel === "nbt");
    if (track?.driver.type === "state") for (const key of track.driver.keys) {
      if (parseMinecraftTime(key.time) > tick) break;
      const patch = key.value as NbtPatchIR;
      for (const name of patch.remove ?? []) delete state[name];
      Object.assign(state, nbtFields(typeof patch.merge === "string" ? patch.merge : undefined));
    }
    return state;
  };
  for (const [tick, boneVisible, itemVisible, radius, brightness] of [
    [0, true, true, "1", "{sky:2,block:3}"], [4, false, false, "4", "{sky:12,block:8}"],
    [8, false, false, "4", "{sky:12,block:8}"], [12, false, true, "4", "{sky:12,block:8}"], [16, true, true, "1", "{sky:2,block:3}"],
  ] as const) {
    const pose = evaluatePose(output, tick / 20, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
    for (const [id] of cubeNodes) {
      expect(pose[id].visible).toBe(boneVisible);
      expect(stateAt(id, tick).shadow_radius).toBe(radius);
      expect(stateAt(id, tick).brightness).toBe(brightness);
    }
    expect(pose.item.visible).toBe(itemVisible);
    expect(pose.excluded.visible).toBe(true);
    expect(stateAt("excluded", tick).shadow_radius).toBe("3");
    expect(stateAt("item", tick).shadow_radius).toBe(tick < 4 || tick >= 16 ? "2" : tick < 12 ? "9" : "7");
  }
  expect(stateAt(cubeNodes[0][0], 8).shadow_strength).toBe("0.5");
  expect(stateAt("item", 8).shadow_strength).toBe("0.25");
  expect(stateAt(cubeNodes[0][0], 16).shadow_strength).toBe("0.5");
  expect(stateAt("item", 16).billboard).toBe('"vertical"');
  expect(evaluatePose(output, 0.6, (value) => Number(value)).child.visible).toBe(false);
  const [json] = await exportAnimation(document, 0);
  expect(JSON.parse(await json.blob.text()).animation.tracks).toEqual(output.animation.tracks);

  const warnings = await importProject({
    elements: [display("item")], outliner: ["item"], variants: { list: [{ uuid: "textured", name: "textured", texture_map: { a: "b" } }] },
    animations: [{ name: "motion", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
      { channel: "variant", time: 0.2, data_points: [{ variant: "textured" }] },
      { channel: "variant", time: 0.4, data_points: [{ variant: "unknown" }] },
      { channel: "unsupported", time: 0.6, data_points: [{}] },
    ] } } }],
  });
  expect(warnings.input.diagnostics.map((issue) => issue.code).sort()).toEqual([
    "animated_java_effect_ignored", "animated_java_unknown_variant", "animated_java_variant_texture_map_ignored",
  ]);
});

it("keeps variant config functions bound to their display entities including generated skin slices and default configs", async () => {
  const bone = { ...group("limb"), name: "left_arm", configs: {
    default: { on_apply_function: "/say bone default" },
    variants: { active: { on_apply_function: "/data merge entity @s {Glowing:1b}\n/say bone active" } },
  } };
  const item = { ...display("item"), configs: {
    default: { on_apply_function: "/say item default" }, variants: { active: { on_apply_function: "/say item active" } },
  } };
  const excluded = { ...display("excluded"), configs: { variants: { active: { on_apply_function: "/say excluded" } } } };
  const { input, document, output } = await importProject({
    elements: [{ uuid: "limb_cube", name: "arm", type: "cube", from: [16, 32, 48], to: [20, 44, 52], faces: {} }, item, excluded], groups: [bone],
    outliner: [{ uuid: "limb", children: ["limb_cube", "item", "excluded"] }],
    variants: { default: { uuid: "default", name: "default", is_default: true, on_apply_function: "/say root default" }, list: [
      { uuid: "active", name: "active", excluded_nodes: ["excluded"], on_apply_function: "/say root active" },
    ] },
    animations: [{ name: "motion", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
      { channel: "variant", time: 0.2, data_points: [{ variant: "active" }] },
      { channel: "variant", time: 0.8, data_points: [{ variant: "default" }] },
    ] } } }],
  });
  expect(input.diagnostics).toEqual([]);
  const slices = Object.keys(output.nodes).filter((id) => output.nodes[id].source?.node_id === "limb_cube");
  expect(slices).toHaveLength(2);
  expect(slices.every((id) => output.nodes[id].attachments?.display.type === "player_skin")).toBe(true);
  const events = output.animation.events!.timeline!;
  for (const [time, phase] of [["4t", "active"], ["16t", "default"]] as const) {
    const phaseEvents = events.filter((event) => event.time === time);
    expect(phaseEvents).toHaveLength(4);
    for (const id of ["item", ...slices]) expect(phaseEvents).toContainEqual({
      time, source: { type: "node", node: id, attachment: "display" }, origin: { type: "root", rotation: "origin" }, action: { type: "commands", commands: id === "item"
        ? [`say item ${phase}`] : phase === "active" ? ["data merge entity @s {Glowing:1b}", "say bone active"] : ["say bone default"] },
    });
    expect(phaseEvents.at(-1)).toEqual({ time, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [`say root ${phase}`] } });
  }
  const [json] = await exportAnimation(document, 0);
  expect(JSON.parse(await json.blob.text()).animation.events.timeline).toEqual(events);
});

it("preserves single-command conditions and skips only animations with conditional state or shared command batches", async () => {
  const project: Partial<AjProject> = {
    elements: [{ ...display("item"), configs: { variants: { state: { invisible: true }, node_command: { on_apply_function: "/say private node" } } } }], outliner: ["item"],
    variants: { list: [
      { uuid: "commands", name: "commands", on_apply_function: "/say conditional variant" }, { uuid: "state", name: "state" },
      { uuid: "batch", name: "batch", on_apply_function: "/tag @s remove private\n/say private batch" },
      { uuid: "node_command", name: "node_command" },
    ] },
    animations: [
      { name: "healthy", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
        { channel: "function", time: 0.1, data_points: [{ function: "/say conditional function", execute_condition: " if entity @s[tag=private] " }] },
        { channel: "commands", time: 0.2, data_points: [{ commands: "/say first\n/say second" }] },
        { channel: "variant", time: 0.4, data_points: [{ variant: "commands", execute_condition: "unless entity @s[tag=private]" }] },
      ] } } },
      { name: "conditional_state", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
        { channel: "variant", time: 0.2, data_points: [{ variant: "state", execute_condition: "if entity @s[tag=private]" }] },
      ] } } },
      { name: "conditional_function_batch", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
        { channel: "function", time: 0.2, data_points: [{ function: "/tag @s remove private\n/say private batch", execute_condition: "if entity @s[tag=private]" }] },
      ] } } },
      { name: "conditional_variant_batch", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
        { channel: "variant", time: 0.2, data_points: [{ variant: "batch", execute_condition: "if entity @s[tag=private]" }] },
      ] } } },
      { name: "conditional_node_command", length: 1, loop: "once", animators: { effects: { type: "effect", keyframes: [
        { channel: "variant", time: 0.2, data_points: [{ variant: "node_command", execute_condition: "if entity @s[tag=private]" }] },
      ] } } },
    ],
  };
  const { input, document, output } = await importProject(project);
  expect(document.animations.map((animation) => animation.sourceName)).toEqual(["Healthy"]);
  expect(output.animation.events!.timeline!.map((event) => event.action)).toEqual([
    { type: "commands", commands: ["execute if entity @s[tag=private] run say conditional function"] },
    { type: "commands", commands: ["say first", "say second"] },
    { type: "commands", commands: ["execute unless entity @s[tag=private] run say conditional variant"] },
  ]);
  expect(input.diagnostics.map((issue) => issue.code)).toEqual(Array(4).fill("animation_skipped"));
  expect(input.diagnostics.map((issue) => issue.sourcePath)).toEqual([1, 2, 3, 4].map((index) => `animations[${index}].animators.effects.keyframes[0]`));
  expect(input.diagnostics[0].message).toContain("4t: conditional variant state changes");
  expect(input.diagnostics[1].message).toContain("one shared condition evaluation");
  expect(JSON.stringify(input.diagnostics)).not.toContain("private");
  const [json] = await exportAnimation(document, 0);
  expect(JSON.parse(await json.blob.text()).animation.events).toEqual(output.animation.events);
  await expect(importProject({ ...project, animations: project.animations!.slice(1, 2) })).rejects.toThrow("No Animated Java animations could be imported");
});

it("exports locator functions with animated node origins and keeps the exact v5 artifact consumed by the mod test", async () => {
  const path = new URL("../../../src/test/resources/aj-function-source.ajblueprint", import.meta.url);
  const input = await readInput("animated_java_blueprint", { name: "aj-function-source.ajblueprint", bytes: await readFile(path) });
  const document = createConversionDocument(input, "AJ");
  const [json] = await exportAnimation(document, 0);
  const output = JSON.parse(await json.blob.text());
  expect(output).toEqual(JSON.parse(await readFile(new URL("../../../src/test/resources/aj-function-export.json", import.meta.url), "utf8")));
  expect(input.diagnostics).toEqual([]);
  const locatorEvents = output.animation.events.timeline.filter((event: { origin: { type: string } }) => event.origin.type === "node");
  expect(locatorEvents.map((event: { time: string; origin: unknown; action: unknown }) => [event.time, event.origin, event.action])).toEqual([
    ["0t", { type: "node", node: "left_arm_tip", rotation: "origin" }, { type: "commands", commands: ["say locator start"] }],
    ["10t", { type: "node", node: "left_arm_tip", rotation: "origin" }, { type: "commands", commands: ["execute if entity @s[tag=probe] run say locator middle"] }],
    ["20t", { type: "node", node: "left_arm_tip", rotation: "origin" }, { type: "commands", commands: ["say locator end"] }],
  ]);
  for (const time of [0, 0.5, 1]) {
    const pose = evaluatePose(output, time, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
    const expected = new Matrix4().makeTranslation(1, 2, 3).multiply(new Matrix4().makeRotationY((30 + 60 * time) * Math.PI / 180))
      .multiply(new Matrix4().makeScale(2, 2, 2)).multiply(new Matrix4().makeTranslation(0.5, 0.25, -0.5))
      .multiply(new Matrix4().makeRotationFromEuler(new Euler(20 * Math.PI / 180, 10 * Math.PI / 180, 15 * Math.PI / 180, "ZYX")));
    expect(pose.left_arm_tip.matrix.elements.every((value, axis) => Math.abs(value - expected.elements[axis]) < 1e-10)).toBe(true);
  }
});
