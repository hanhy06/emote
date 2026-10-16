import { readFile } from "node:fs/promises";
import { expect, it } from "vitest";
import { createConversionDocument, assignSkinPart } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { readInput } from "../import/formats";
import type { BbCube } from "../import/common/blockbenchCubeSchema";
import { attachmentTargetId, orderedNodeIds } from "../domain/animationIR";
import { evaluatePose } from "../domain/animationIRPose";
import { exportAnimation } from "../export/projectExporter";
import { strFromU8, unzipSync } from "fflate";
import { Euler, Matrix4, Vector3 } from "three";

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

it("preserves root cube pivots and locator coordinates beside unnamed groups and exports only used textures", async () => {
  const source = cube("loose", [16, 24, 32], [18, 28, 34], {
    origin: [20, 30, 40], rotation: [10, 20, 30], faces: { north: { uv: [0, 0, 16, 16], texture: "used" } },
  });
  const locator = { uuid: "tip", name: "tip", type: "locator", position: [48, 64, 80], rotation: [10, 0, 20] };
  const texture = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
  const radians = (value: number) => value * Math.PI / 180;
  const expected = new Matrix4().makeScale(0.9375, 0.9375, 0.9375)
    .multiply(new Matrix4().makeTranslation(20 / 16, 30 / 16, 40 / 16))
    .multiply(new Matrix4().makeRotationFromEuler(new Euler(radians(10), radians(20), radians(30), "ZYX")))
    .multiply(new Matrix4().makeTranslation(-20 / 16, -30 / 16, -40 / 16));
  for (const format of formats) {
    const project = await importModel(format, "", [source, cube("nested", [0, 0, 0], [2, 2, 2], { faces: {} })], {
      elements: [source, cube("nested", [0, 0, 0], [2, 2, 2], { faces: {} }), locator],
      groups: [{ uuid: "root", name: "", origin: [16, 0, 0], rotation: [0, 0, 90] }],
      outliner: [{ uuid: "root", children: ["nested"] }, "loose", "tip"],
      textures: [{ uuid: "unused", source: "file:///unused.png" }, { uuid: "used", source: `data:image/png;base64,${texture}` }],
    });
    const document = createConversionDocument(project, format);
    const output = compileAnimation(document, 0).animation;
    expect(output.nodes["group:root"].name).toBe("");
    expect(orderedNodeIds(output.nodes)).toHaveLength(Object.keys(output.nodes).length);
    const pose = evaluatePose(output, 0, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
    const cubeId = Object.keys(output.nodes).find((id) => output.nodes[id].source?.node_id === "loose")!;
    const model = [...document.resources.values()].find((resource) => !(resource instanceof Uint8Array) && resource.kind === "cuboid_model" && Object.keys(resource.textures).length > 0);
    if (!model || model instanceof Uint8Array || model.kind !== "cuboid_model") throw new Error("Expected textured cube model");
    const from = model.elements[0].from as number[], to = model.elements[0].to as number[];
    for (let corner = 0; corner < 8; corner++) {
      const point = new Vector3(...[0, 1, 2].map((axis) => ((corner & (1 << axis) ? source.to : source.from)[axis]) / 16) as [number, number, number]);
      const actual = new Vector3(...[0, 1, 2].map((axis) => ((corner & (1 << axis) ? to : from)[axis] - 8) / 16) as [number, number, number]).applyMatrix4(pose[cubeId].matrix);
      expect(actual.distanceTo(point.applyMatrix4(expected))).toBeLessThan(1e-8);
    }
    const locatorId = Object.keys(output.nodes).find((id) => output.nodes[id].source?.node_id === "tip")!;
    expect(new Vector3().setFromMatrixPosition(pose[locatorId].matrix).toArray()).toEqual([2.8125, 3.75, 4.6875]);
    const files = await exportAnimation(document, 0);
    const archive = unzipSync(new Uint8Array(await files.find((file) => file.fileName.endsWith(".zip"))!.blob.arrayBuffer()));
    expect(Object.keys(archive).filter((path) => path.endsWith(".png"))).toEqual(["textures/model]textures]item]model]texture_1.png"]);
    expect(archive["textures/model]textures]item]model]texture_1.png"]).toEqual(Uint8Array.from(atob(texture), (character) => character.charCodeAt(0)));
  }
});

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

it("normalizes oversized or displaced models without changing their animated geometry or skin fitting", async () => {
  const groupOrigin = [16, 32, -16];
  const groupRotation = [10, -20, 5];
  const radians = (degrees: number) => degrees * Math.PI / 180;
  for (const format of formats) for (const source of [
    cube("wide", [0, 0, 0], [64, 2, 2]),
    cube("displaced", [100, 20, 30], [102, 22, 32]),
    cube("rotated", [-80, -16, 8], [64, 24, 40], { origin: [-16, 8, 24], rotation: [25, 35, -15], inflate: 0.5 }),
  ]) {
    const input = await importModel(format, "prop", [source], {
      groups: [{ uuid: "bone", name: "prop", origin: groupOrigin, rotation: groupRotation }],
      animations: [{ name: "idle", length: 1, loop: "once", animators: { bone: { keyframes: [
        { channel: "rotation", time: 0, data_points: [{ x: 0, y: 0, z: 0 }] },
        { channel: "rotation", time: 1, data_points: [{ x: 0, y: 30, z: 0 }] },
        { channel: "position", time: 0, data_points: [{ x: 0, y: 0, z: 0 }] },
        { channel: "position", time: 1, data_points: [{ x: 16, y: 8, z: -16 }] },
      ] } } }],
    });
    const document = createConversionDocument(input, format);
    const original = compileAnimation(document, 0).animation;
    const nodeId = Object.keys(original.nodes).find((id) => original.nodes[id].attachments?.display)!;
    expect(original.nodes[nodeId].transform?.some((operation) => operation.id === "model_geometry")).toBe(true);
    const model = [...document.resources.values()].find((resource) => !(resource instanceof Uint8Array) && resource.kind === "cuboid_model");
    if (!model || model instanceof Uint8Array || model.kind !== "cuboid_model") throw new Error("Expected generated model");
    const from = model.elements[0].from as number[], to = model.elements[0].to as number[];
    expect([...from, ...to].every((value) => Number.isFinite(value) && value >= -16 && value <= 32)).toBe(true);
    expect(model.elements[0].faces).toEqual({ north: { uv: [0, 0, 4, 4], texture: "#layer0" } });
    const pivot = (source.origin ?? groupOrigin).map((value, axis) => (value - groupOrigin[axis]) / 16);
    const rotation = source.rotation ?? [0, 0, 0];
    const cubeRotation = new Matrix4().makeTranslation(...pivot as [number, number, number])
      .multiply(new Matrix4().makeRotationFromEuler(new Euler(...rotation.map(radians) as [number, number, number], "ZYX")))
      .multiply(new Matrix4().makeTranslation(-pivot[0], -pivot[1], -pivot[2]));
    const skinned = compileAnimation(assignSkinPart(document, new Set([attachmentTargetId(nodeId, "display")]), "head"), 0).animation;
    for (const time of [0, 0.5, 1]) {
      const sceneScale = 0.9375;
      const expected = new Matrix4().makeScale(sceneScale, sceneScale, sceneScale)
        .multiply(new Matrix4().makeTranslation(groupOrigin[0] / 16 + time, groupOrigin[1] / 16 + time * 0.5, groupOrigin[2] / 16 - time))
        .multiply(new Matrix4().makeRotationFromEuler(new Euler(radians(groupRotation[0]), radians(groupRotation[1] + time * 30), radians(groupRotation[2]), "ZYX")))
        .multiply(cubeRotation);
      const actual = evaluatePose(original, time, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; })[nodeId].matrix;
      for (let corner = 0; corner < 8; corner++) {
        const coordinates = [0, 1, 2].map((axis) => (corner & (1 << axis)) !== 0);
        const point = new Vector3(...coordinates.map((upper, axis) => ((upper ? to : from)[axis] - 8) / 16) as [number, number, number]).applyMatrix4(actual);
        const reference = new Vector3(...coordinates.map((upper, axis) => ((upper ? source.to : source.from)[axis] - groupOrigin[axis] + (upper ? 1 : -1) * (source.inflate ?? 0)) / 16) as [number, number, number]).applyMatrix4(expected);
        expect(point.distanceTo(reference)).toBeLessThan(1e-8);
      }
      const center = source.from.map((value, axis) => ((value + source.to[axis]) / 2 - groupOrigin[axis]) / 16);
      const size = source.to.map((value, axis) => (value - source.from[axis] + (source.inflate ?? 0) * 2) / 8);
      const fitting = expected.clone().multiply(new Matrix4().makeTranslation(...center as [number, number, number]))
        .multiply(new Matrix4().makeScale(...size as [number, number, number])).multiply(new Matrix4().makeTranslation(0, 0.25, 0));
      const skinPose = evaluatePose(skinned, time, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; })[nodeId].matrix;
      expect(skinPose.elements.every((value, axis) => Math.abs(value - fitting.elements[axis]) < 1e-8)).toBe(true);
    }
    const restored = assignSkinPart(assignSkinPart(document, new Set([attachmentTargetId(nodeId, "display")]), "head"), new Set([attachmentTargetId(nodeId, "display")]), null);
    expect(compileAnimation(restored, 0).animation).toEqual(original);
    const outputs = await exportAnimation(restored, 0);
    const zip = outputs.find((output) => output.fileName.endsWith(".zip"));
    expect(zip).toBeDefined();
    const files = unzipSync(new Uint8Array(await zip!.blob.arrayBuffer()));
    const modelPath = Object.keys(files).find((path) => path.includes("]models]item]"))!;
    expect(JSON.parse(strFromU8(files[modelPath])).elements).toEqual(model.elements);
  }
});
