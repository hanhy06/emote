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
