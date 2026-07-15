import JSZip from "jszip";
import { describe, expect, it } from "vitest";
import { isZip, loadPack } from "./datapackArchive";

describe("datapackArchive", () => {
  it("loads files relative to a nested datapack root", async () => {
    const zip = new JSZip();
    zip.file("export/pack.mcmeta", "{}");
    zip.file("export/data/demo/function/test.mcfunction", "say test");
    const bytes = await zip.generateAsync({ type: "uint8array" });

    expect(isZip(bytes)).toBe(true);
    const pack = await loadPack({ name: "demo.zip", bytes });
    expect([...pack.files.keys()].sort()).toEqual(["data/demo/function/test.mcfunction", "pack.mcmeta"]);
  });
});
