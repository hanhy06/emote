import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { findEmoteModels, parseKeyframeMatrices, parsePlayerHeadParts } from "./partParser";
import { loadDatapack } from "./packFileSystem";

describe("parsePlayerHeadParts", () => {
  it("reads part index, scale, and transformed center", () => {
    const text = 'summon item_display ~ ~ ~ {id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{}},transformation:[0.5f,0f,0f,1f,0f,1f,0f,2f,0f,0f,0.25f,3f,0f,0f,0f,1f],Tags:["demo_7"]}';

    const [part] = parsePlayerHeadParts(text, "demo");

    expect(part.partIndex).toBe(7);
    expect(part.scale).toEqual({ x: 0.5, y: 1, z: 0.25 });
    expect(part.anchor).toEqual({ x: 1, y: 2.5, z: 3 });
  });
});

describe("parseKeyframeMatrices", () => {
  it("reads the first-frame matrix for each matching display tag", () => {
    const text = 'data merge entity @e[type=item_display,tag=demo_7,distance=..1] {transformation:[1f,0f,0f,4f,0f,1f,0f,5f,0f,0f,1f,6f,0f,0f,0f,1f],interpolation_duration:0}';

    const matrices = parseKeyframeMatrices(text, "demo");

    expect(matrices.get(7)?.slice(3, 12)).toEqual([4, 0, 1, 0, 5, 0, 0, 1, 6]);
  });
});

describe("findEmoteModels", () => {
  it.each(["cry", "hello", "no", "yes"])("extracts every player head from the %s example", async (name) => {
    const bytes = await readFile(new URL(`../../../docs/example/emote.${name}.zip`, import.meta.url));
    const blob = new Blob([bytes]) as Blob & { name?: string };
    blob.name = `emote.${name}.zip`;

    const datapack = await loadDatapack(blob);
    const models = findEmoteModels(datapack);
    const firstFrame = parseKeyframeMatrices(
      new TextDecoder().decode(datapack.files.get(`data/${name}/function/k/default/keyframe_0.mcfunction`)),
      name,
    );

    expect(models).toHaveLength(1);
    expect(models[0].namespace).toBe(name);
    expect(models[0].previewFrames.length).toBeGreaterThan(1);
    expect(models[0].previewFrames[0].animation).toBe("default");
    expect(models[0].previewFrames[0].frameIndex).toBe(0);
    expect(models[0].parts).toHaveLength(11);
    expect(models[0].parts.every((part) => {
      const frameMatrix = firstFrame.get(part.partIndex);
      return !frameMatrix || part.matrix.every((value, index) => value === frameMatrix[index]);
    })).toBe(true);
    expect(models[0].previewFrames.every((frame) => frame.parts.length === 11)).toBe(true);
  });
});
