import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { findEmoteModels, parsePlayerHeadParts } from "./partParser";
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

describe("findEmoteModels", () => {
  it.each(["cry", "hello", "no", "yes"])("extracts every player head from the %s example", async (name) => {
    const bytes = await readFile(new URL(`../../../docs/example/emote.${name}.zip`, import.meta.url));
    const blob = new Blob([bytes]) as Blob & { name?: string };
    blob.name = `emote.${name}.zip`;

    const models = findEmoteModels(await loadDatapack(blob));

    expect(models).toHaveLength(1);
    expect(models[0].namespace).toBe(name);
    expect(models[0].parts).toHaveLength(11);
  });
});
