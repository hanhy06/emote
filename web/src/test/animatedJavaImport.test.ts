import { expect, it } from "vitest";
import { Euler, Matrix4 } from "three";
import { createConversionDocument } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { evaluatePose } from "../domain/animationIRPose";
import { readInput } from "../import/formats";
import { parseSnbtCompound } from "../format/snbt";
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
  expect(track?.driver).toEqual({ type: "state", keys: [
    { time: "0t", value: { merge: "{}", remove: ["brightness"] } },
    { time: "10t", value: { merge: "{brightness:{sky:5,block:2}}" } },
  ] });
});
