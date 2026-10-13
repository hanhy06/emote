import { readFile } from "node:fs/promises";
import { expect, it } from "vitest";
import { createConversionDocument, assignSkinPart } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { readInput } from "../import/formats";
import type { BbCube } from "../import/common/blockbenchCubeSchema";
import { orderedNodeIds } from "../domain/animationIR";

const formats = ["geckolib_bbmodel", "animated_java_blueprint"] as const;

function cube(uuid: string, from: number[], to: number[], fields: Partial<BbCube> = {}): BbCube {
  return { uuid, name: uuid, type: "cube", from, to, faces: { north: { uv: [0, 0, 16, 16], texture: 0 } }, ...fields };
}

async function importModel(format: typeof formats[number], name: string, cubes: BbCube[], extra: Record<string, unknown> = {}) {
  const project = {
    meta: { model_format: "geckolib_model", format: "animated-java:format/blueprint", format_version: "1.10.2" },
    name: "model", resolution: { width: 64, height: 64 }, textures: [], elements: cubes,
    groups: [{ uuid: "bone", name, origin: [0, 0, 0], rotation: [0, 0, 0] }],
    outliner: [{ uuid: "bone", children: cubes.map((cube) => cube.uuid) }],
    animations: [{ name: "idle", length: 1, loop: "once", animators: {} }], ...extra,
  };
  return readInput(format, { name: "model", bytes: new TextEncoder().encode(JSON.stringify(project)) });
}

it("preserves decorations and unaligned limb geometry through both model input paths", async () => {
  for (const format of formats) for (const [name, cubes] of [
    ["prop", [cube("base", [0, 0, 0], [2, 2, 2]), cube("decoration", [0, 0, 0], [2, 2, 2], { inflate: 0.2 })]],
    ["prop", [cube("overlay_a", [0, 0, 0], [2, 2, 2], { inflate: 0.2 }), cube("overlay_b", [0, 0, 0], [2, 2, 2], { inflate: 0.3 })]],
    ["left_arm", [cube("low", [0, 0, 0], [4, 7, 4]), cube("high", [0, 7, 0], [4, 12, 4])]],
  ] as const) {
    const input = await importModel(format, name, [...cubes]);
    const document = createConversionDocument(input, format);
    const output = compileAnimation(document, 0);
    const displays = Object.values(output.animation.nodes).flatMap((node) => Object.values(node.attachments ?? {}));
    expect(displays).toHaveLength(2);
    expect(displays.every((attachment) => attachment.type === "item_display")).toBe(true);
    const models = [...document.resources.values()].filter((resource) => !(resource instanceof Uint8Array) && resource.kind === "cuboid_model");
    expect(models).toHaveLength(2);
    for (const [index, model] of models.entries()) {
      if (model instanceof Uint8Array || model.kind !== "cuboid_model") throw new Error("Expected generated cube model");
      const source = cubes[index];
      expect(model.elements[0]).toMatchObject({
        from: source.from.map((value) => value - (source.inflate ?? 0) + 8),
        to: source.to.map((value) => value + (source.inflate ?? 0) + 8),
      });
    }
    if (name === "left_arm") expect(input.diagnostics.some((issue) => issue.code === "blockbench_skin_split_preserved")).toBe(true);
  }
});

it("excludes actual skin overlays while preserving skin assignment and restoration", async () => {
  for (const format of formats) {
    const input = await importModel(format, "head", [
      cube("head", [0, 0, 0], [8, 8, 8], { faces: { north: { uv: [8, 8, 16, 16], texture: 0 } } }),
      cube("headwear", [0, 0, 0], [8, 8, 8], { inflate: 0.5, faces: { north: { uv: [40, 8, 48, 16], texture: 0 } } }),
      cube("decoration", [0, 0, 0], [8, 8, 8], { inflate: 0.2, faces: { north: { uv: [8, 8, 16, 16], texture: 0 } } }),
    ]);
    const document = createConversionDocument(input, format);
    const attachments = Object.values(document.nodes).flatMap((node) => Object.values(node.attachments ?? {}));
    expect(attachments.filter((attachment) => attachment.type === "player_skin")).toHaveLength(1);
    expect(attachments.filter((attachment) => attachment.type === "item_display")).toHaveLength(1);
    const restored = assignSkinPart(document, new Set(Object.keys(document.skinCandidates)), null);
    expect(Object.values(restored.nodes).flatMap((node) => Object.values(node.attachments ?? {})).every((attachment) => attachment.type === "item_display")).toBe(true);
  }
  const path = "docs/reference/aj/sit.ajblueprint";
  const input = await readInput("animated_java_blueprint", { name: "sit.ajblueprint", bytes: await readFile(new URL(`../../../${path}`, import.meta.url)) });
  const document = createConversionDocument(input, "AJ");
  const index = document.animations.findIndex((entry) => entry.sourceName === "sit_down");
  expect(index).toBeGreaterThanOrEqual(0);
  const output = compileAnimation(document, index);
  expect(output.generatedResourceReferences.size).toBe(0);
  expect(Object.values(output.animation.nodes).flatMap((node) => Object.values(node.attachments ?? {})).filter((attachment) => attachment.type === "player_skin")).toHaveLength(27);
});

it("keeps reserved IDs distinct and routes source cube state to every generated slice", async () => {
  for (const format of formats) {
    const input = await importModel(format, "scene", [cube("cube", [0, 0, 0], [2, 2, 2])]);
    const document = createConversionDocument(input, format);
    const output = compileAnimation(document, 0).animation;
    expect(orderedNodeIds(output.nodes)).toHaveLength(3);
    expect(output.nodes.scene.parent).toBeUndefined();
    expect(output.nodes["group:bone"].parent).toBe("scene");
    const content = Object.values(output.nodes).find((node) => node.attachments?.display);
    expect(content?.parent).toBe("group:bone");
    expect(content?.source?.node_id).toBe("cube");
  }
  const input = await importModel("animated_java_blueprint", "left_arm", [cube("limb", [0, 0, 0], [4, 12, 4])], {
    elements: [cube("limb", [0, 0, 0], [4, 12, 4]), {
      uuid: "left_arm", name: "separate display", type: "animated_java:vanilla_item_display",
      position: [16, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], visibility: true, item: "minecraft:stone",
    }],
    outliner: [{ uuid: "bone", children: ["limb"] }, "left_arm"],
    animations: [{ name: "idle", length: 1, loop: "once", animators: {
      limb: { keyframes: [{ channel: "visibility", time: 0.5, data_points: [{ x: 0 }] }] },
    } }],
  });
  const document = createConversionDocument(input, "AJ");
  const output = compileAnimation(document, 0).animation;
  expect(output.nodes.left_arm.attachments?.display).toMatchObject({ type: "item_display", item_stack_snbt: '{id:"minecraft:stone",count:1}' });
  const slices = Object.entries(output.nodes).filter(([, node]) => node.source?.node_id === "limb");
  expect(slices).toHaveLength(2);
  expect(output.animation.tracks.filter((track) => track.channel === "visible").map((track) => track.target.node).sort()).toEqual(slices.map(([id]) => id).sort());
  expect(input.diagnostics).toEqual([]);
});
