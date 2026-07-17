import { describe, expect, it } from "vitest";
import { strFromU8, strToU8, unzipSync, zipSync } from "fflate";
import type { ImportedProject } from "../import/types";
import type { ExportOptions } from "./projectExporter";
import { mergeResourcePackFolder, mergeResourcePackZip } from "./resourcePackMerger";

const project = {
  source: "animated_java_json",
  sourceName: "emote.ajblueprint",
  suggestedMetadata: { name: "Emote", description: "", hide_player: false },
  artifactMinecraftVersion: "26.2",
  nodes: {},
  animations: [],
  diagnostics: [],
  artifacts: new Map([
    ["assets/emote/models/item/model.json", strToU8("new model")],
    ["assets/emote/textures/item/texture.png", new Uint8Array([1, 2, 3])],
  ]),
} satisfies ImportedProject;

const options: ExportOptions = {
  minecraftVersion: "26.2",
  namespace: "emote",
  name: "Emote",
  description: "",
  hide_player: false,
  playbackMode: "source",
};

describe("resource pack merger", () => {
  it("preserves a ZIP pack and overwrites conflicts with generated emote resources", async () => {
    const source = zipSync({
      "Original Pack/pack.mcmeta": strToU8('{"pack":{"description":"original","pack_format":88}}'),
      "Original Pack/pack.png": new Uint8Array([9]),
      "Original Pack/assets/emote/models/item/model.json": strToU8("old model"),
      "Original Pack/assets/other/file.txt": strToU8("keep"),
    });
    const file = new File([source], "original.zip");

    const result = await mergeResourcePackZip(project, options, file);
    const merged = unzipSync(new Uint8Array(await result.blob.arrayBuffer()));

    expect(result.fileName).toBe("original.emote-merged.zip");
    expect(strFromU8(merged["pack.mcmeta"])).toContain("original");
    expect(merged["pack.png"]).toEqual(new Uint8Array([9]));
    expect(strFromU8(merged["assets/other/file.txt"])).toBe("keep");
    expect(strFromU8(merged["assets/emote/models/item/model.json"])).toBe("new model");
    expect(merged["assets/emote/textures/item/texture.png"]).toEqual(new Uint8Array([1, 2, 3]));
  });

  it("recognizes a selected resource pack folder and produces a root-level pack ZIP", async () => {
    const folderFiles = [
      folderFile("My Pack/pack.mcmeta", "metadata"),
      folderFile("My Pack/assets/base/file.txt", "base"),
    ];

    const result = await mergeResourcePackFolder(project, options, folderFiles);
    const merged = unzipSync(new Uint8Array(await result.blob.arrayBuffer()));

    expect(result.fileName).toBe("My Pack.emote-merged.zip");
    expect(strFromU8(merged["pack.mcmeta"])).toBe("metadata");
    expect(strFromU8(merged["assets/base/file.txt"])).toBe("base");
    expect(merged["My Pack/pack.mcmeta"]).toBeUndefined();
  });

  it("rejects a selection that does not contain a resource pack", async () => {
    await expect(mergeResourcePackFolder(project, options, [folderFile("folder/readme.txt", "no pack")]))
      .rejects.toThrow("Could not find pack.mcmeta");
  });
});

function folderFile(path: string, contents: string) {
  return {
    name: path.split("/").at(-1) ?? path,
    webkitRelativePath: path,
    async arrayBuffer() {
      return strToU8(contents).buffer as ArrayBuffer;
    },
  };
}
