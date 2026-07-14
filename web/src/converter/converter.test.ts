import JSZip from "jszip";
import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { convertDatapack } from "./converter";
import { loadDatapack, type LoadedDatapack } from "./packFileSystem";
import { findEmoteModels } from "./partParser";

const encoder = new TextEncoder();

describe("convertDatapack", () => {
  it("writes schema 3 metadata and assigned markers to an example pack", async () => {
    const bytes = await readFile(new URL("../../../docs/example/emote.hello.zip", import.meta.url));
    const blob = new Blob([bytes]) as Blob & { name?: string };
    blob.name = "hello.zip";
    const datapack = await loadDatapack(blob);
    const models = findEmoteModels(datapack);
    const assignments = Object.fromEntries(models.map((model) => [model.namespace, Object.fromEntries(
      model.parts.map((part) => [part.partIndex, part.existingAssignment]),
    )]));

    const result = await convertDatapack(datapack, models, assignments, {}, {
      name: "Hello",
      description: "Hello emote.",
      commandName: "hello",
      hidePlayer: true,
    });
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer());
    const metadata = JSON.parse(await zip.file("data/hello/emote.json")!.async("text"));

    expect(result.fileName).toBe("emote.hello.zip");
    expect(metadata).toMatchObject({ schema_version: 3, command_name: "hello", hide_player: true });
    expect(await zip.file("data/hello/function/_/create.mcfunction")!.async("text")).toContain('name:"emote:head"');
  });

  it("splits multiple animation directories into isolated namespaces", async () => {
    const files = new Map<string, Uint8Array>([
      ["pack.mcmeta", encoder.encode("{}")],
      ["data/demo/function/_/create.mcfunction", encoder.encode('summon item_display ~ ~ ~ {id:"minecraft:item_display",item:{id:"minecraft:player_head",components:{}},Tags:["demo_0"]}')],
      ["data/demo/function/a/first/play_anim_loop.mcfunction", encoder.encode("function demo:k/first/start")],
      ["data/demo/function/a/second/play_anim_loop.mcfunction", encoder.encode("function demo:k/second/start")],
      ["data/demo/function/k/first/start.mcfunction", encoder.encode("say first")],
      ["data/demo/function/k/second/start.mcfunction", encoder.encode("say second")],
    ]);
    const datapack: LoadedDatapack = { fileName: "demo.zip", rootPath: "", files };
    const models = findEmoteModels(datapack);

    const result = await convertDatapack(datapack, models, { demo: { 0: "head" } }, {}, {
      name: "Demo",
      description: "Demo emote.",
      commandName: "demo",
      hidePlayer: true,
    });
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer());

    expect(result.targets.map((target) => target.namespace)).toEqual(["demo_1", "demo_2"]);
    expect(zip.file("data/demo_1/function/a/first/play_anim_loop.mcfunction")).not.toBeNull();
    expect(zip.file("data/demo_1/function/a/second/play_anim_loop.mcfunction")).toBeNull();
    expect(await zip.file("data/demo_2/function/a/second/play_anim_loop.mcfunction")!.async("text")).toContain("demo_2:k/second/start");
  });
});
