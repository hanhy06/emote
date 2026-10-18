import { expect, it } from "vitest";
import { encode } from "fast-png";
import { readInput } from "../import/formats";
import { createConversionDocument, skinPartOrders } from "../domain/conversionDocument";
import { compileAnimation } from "../compiler/animationCompiler";
import { composeSkin, createSkinOverride, decodeSkinPng, encodeSkinPng } from "../domain/skin";
import { exportAnimation } from "../export/projectExporter";
import { createPreviewModel } from "../preview/previewModel";
import { createPlayerHeadGeometry } from "../components/playerHeadGeometry";
import { Matrix4, Vector3 } from "three";
import { evaluatePose } from "../domain/animationIRPose";

const formats = ["geckolib_bbmodel", "animated_java_blueprint"] as const;
const offset = (x: number, y: number) => (y * 64 + x) * 4;

function image(color: readonly number[]): Uint8Array {
  const pixels = new Uint8Array(64 * 64 * 4);
  for (let i = 0; i < pixels.length; i += 4) pixels.set(color, i);
  return pixels;
}

function texture(name: string, base64: string) { return { name, source: `data:image/png;base64,${base64}` }; }

function project(textures: { name: string; source: string }[]) {
  return {
    meta: { model_format: "geckolib_model", format: "animated-java:format/blueprint", format_version: "1.10.2" },
    name: "skin", resolution: { width: 64, height: 64 }, textures,
    groups: [{ uuid: "head", name: "head", origin: [0, 24, 0], rotation: [0, 0, 0] }],
    elements: [{ uuid: "cube", type: "cube", from: [-4, 24, -4], to: [4, 32, 4], faces: { north: { uv: [8, 8, 16, 16], texture: 0 } } }],
    outliner: [{ uuid: "head", children: ["cube"] }],
    animations: [{ name: "idle", length: 1, loop: "once", animators: {} }, { name: "wave", length: 2, loop: "once", animators: {} }],
  };
}

for (const format of formats) it(`${format}: extracts unreferenced named textures and preserves default/override through JSON export and reopen`, async () => {
  const base = encodeSkinPng(image([21, 43, 65, 255]));
  const pixels = image([0, 0, 0, 0]);
  pixels.set([200, 40, 60, 255], offset(8, 8));
  pixels.set([80, 90, 100, 64], offset(41, 8));
  const source = project([texture("skin.png", base), texture("OVERRIDE.PNG", encodeSkinPng(pixels))]);
  const imported = await readInput(format, { name: "skin", bytes: new TextEncoder().encode(JSON.stringify(source)) });
  expect(imported.animations).toHaveLength(2);
  expect(imported.animations[0].ir.skin).toEqual(imported.animations[1].ir.skin);
  const skin = imported.animations[0].ir.skin!;
  const mask = decodeSkinPng(skin.override!.mask);
  expect([...mask.slice(offset(8, 8), offset(8, 8) + 4)]).toEqual([255, 255, 255, 255]);
  expect([...mask.slice(offset(40, 8), offset(40, 8) + 4)]).toEqual([0, 0, 0, 255]);
  expect(mask[offset(41, 8)]).toBe(255);
  const document = createConversionDocument(imported, format);
  document.animations[0].skin = { ...skin, default: base };
  const files = await exportAnimation(document, 0);
  expect(files).toHaveLength(1);
  const bytes = new Uint8Array(await files[0].blob.arrayBuffer());
  const reopened = createConversionDocument(await readInput("emote_json", { name: "skin.json", bytes }), "Emote");
  expect(compileAnimation(reopened, 0).animation.skin).toEqual(document.animations[0].skin);
  expect(reopened.animations[0].skin).not.toBe(document.animations[0].skin);
  const preview = createPreviewModel(reopened, reopened.animations[0], { type: "create" });
  expect(preview.availability).toEqual({ status: "full" });
  expect(preview.parts).toHaveLength(1);
  const candidate = reopened.skinCandidates[preview.parts[0].candidateId];
  expect(reopened.nodes[candidate.nodeId].attachments?.[candidate.attachmentId]).toMatchObject({ type: "player_skin", part: "head", region: { from: 0, to: 1 } });
});

for (const format of formats) it(`${format}: honors an explicit mask and rejects duplicate or incomplete skin texture declarations`, async () => {
  const base = encodeSkinPng(image([21, 43, 65, 255]));
  const pixels = image([0, 0, 0, 0]);
  pixels.set([200, 40, 60, 64], offset(8, 8));
  pixels.set([80, 90, 100, 64], offset(41, 8));
  const mask = image([0, 0, 0, 255]);
  mask.set([255, 255, 255, 255], offset(8, 8));
  mask.set([255, 255, 255, 255], offset(40, 8));
  mask.set([255, 255, 255, 255], offset(41, 8));
  const named = [texture("skin", base), texture("override", encodeSkinPng(pixels)), texture("override_mask.png", encodeSkinPng(mask))];
  const input = (textures: typeof named) => ({ name: "skin", bytes: new TextEncoder().encode(JSON.stringify(project(textures))) });
  const imported = await readInput(format, input(named));
  const skin = { ...imported.animations[0].ir.skin!, default: base };
  expect(skin.override!.mask).toBe(named[2].source.slice("data:image/png;base64,".length));
  const result = composeSkin(skin);
  expect([...result.slice(offset(8, 8), offset(8, 8) + 4)]).toEqual([200, 40, 60, 255]);
  expect([...result.slice(offset(40, 8), offset(40, 8) + 4)]).toEqual([0, 0, 0, 0]);
  expect([...result.slice(offset(41, 8), offset(41, 8) + 4)]).toEqual([80, 90, 100, 64]);
  expect([...result.slice(offset(9, 8), offset(9, 8) + 4)]).toEqual([21, 43, 65, 255]);
  await expect(readInput(format, input([...named, named[1]]))).rejects.toMatchObject({ code: "duplicate_skin_texture" });
  await expect(readInput(format, input([named[0], named[2]]))).rejects.toMatchObject({ code: "missing_skin_override" });
  await expect(readInput(format, input([named[0], { name: "override", source: "file:///skin.png" }]))).rejects.toMatchObject({ code: "external_skin_texture" });
  await expect(readInput(format, input([named[0], named[1], texture("override_mask", encodeSkinPng(image([128, 128, 128, 255])))]))).rejects.toMatchObject({ code: "invalid_skin_texture" });
});

it("creates masks from indexed PNG transparency and rejects non-skin image sizes", () => {
  const indexes = new Uint8Array(64 * 64);
  indexes[8 * 64 + 8] = 1;
  const bytes = encode({ width: 64, height: 64, channels: 1, depth: 8, data: indexes, palette: [[0, 0, 0, 0], [12, 34, 56, 255]] });
  const indexed = btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""));
  const override = createSkinOverride(indexed);
  const mask = decodeSkinPng(override.mask);
  expect(mask[offset(8, 8)]).toBe(255);
  expect(mask[offset(9, 8)]).toBe(0);
  expect([...decodeSkinPng(indexed).slice(offset(8, 8), offset(8, 8) + 4)]).toEqual([12, 34, 56, 255]);
  const wrongSize = encode({ width: 32, height: 64, channels: 4, data: new Uint8Array(32 * 64 * 4) });
  expect(() => createSkinOverride(btoa(Array.from(wrongSize, (byte) => String.fromCharCode(byte)).join("")))).toThrow("64×64");
});

for (const format of formats) it(`${format}: preserves automatic orders and unequal leg slices through preview and output`, async () => {
  const source = project([]);
  source.groups = [{ uuid: "leg", name: "right_leg", origin: [1.9, 12, 0], rotation: [0, 0, 0] }];
  source.elements[0] = { ...source.elements[0], from: [-0.1, 0, -2], to: [3.9, 12, 2] };
  source.outliner = [{ uuid: "leg", children: ["cube"] }];
  const input = await readInput(format, { name: "leg", bytes: new TextEncoder().encode(JSON.stringify(source)) });
  const document = createConversionDocument(input, format);
  const preview = createPreviewModel(document, document.animations[0], { type: "create" });
  const orders = skinPartOrders(document);
  expect(preview.parts.map((part) => orders[part.candidateId])).toEqual([0, 1]);
  const attachments = preview.parts.map((part) => {
    const candidate = document.skinCandidates[part.candidateId];
    return document.nodes[candidate.nodeId].attachments![candidate.attachmentId];
  });
  expect(attachments.map((attachment) => attachment.type === "player_skin" ? attachment.region : null)).toEqual([{ from: 0, to: 1 / 3 }, { from: 1 / 3, to: 1 }]);
  const output = compileAnimation(document, 0).animation;
  const poses = evaluatePose(output, 0, (value) => { if (typeof value !== "number") throw new Error("Unexpected expression"); return value; });
  const geometry = createPlayerHeadGeometry();
  geometry.computeBoundingBox();
  const boxes = preview.parts.map((part) => {
    const matrix = new Matrix4().fromArray(part.matrix).transpose().multiply(new Matrix4().fromArray(part.conversionMatrix!).transpose());
    expect(matrix.elements).toEqual(poses[document.skinCandidates[part.candidateId].nodeId].matrix.elements);
    return geometry.boundingBox!.clone().applyMatrix4(matrix);
  });
  geometry.dispose();
  expect(boxes[0].getSize(new Vector3()).toArray()).toEqual([4 / 16 * 0.9375, 4 / 16 * 0.9375, 4 / 16 * 0.9375]);
  expect(boxes[1].getSize(new Vector3()).toArray()).toEqual([4 / 16 * 0.9375, 8 / 16 * 0.9375, 4 / 16 * 0.9375]);
  expect(boxes[0].min.y).toBe(boxes[1].max.y);
  expect(boxes[0].max.y - boxes[1].min.y).toBe(12 / 16 * 0.9375);
  expect(Object.values(output.nodes).flatMap((node) => Object.values(node.attachments ?? {}))).toEqual(attachments);
});
