import JSZip from "jszip";
import { describe, expect, it } from "vitest";
import { findPackRoot, loadDatapack } from "./packFileSystem";

describe("findPackRoot", () => {
  it("selects the shallowest pack root", () => {
    expect(findPackRoot(["wrapper/pack.mcmeta", "wrapper/nested/pack.mcmeta"])).toBe("wrapper/");
  });

  it("rejects archives without pack metadata", () => {
    expect(() => findPackRoot(["data/demo/function/test.mcfunction"])).toThrow("pack.mcmeta");
  });
});

describe("loadDatapack", () => {
  it("loads files relative to the detected pack root", async () => {
    const zip = new JSZip();
    zip.file("export/pack.mcmeta", "{}");
    zip.file("export/data/demo/function/_/create.mcfunction", "say test");
    zip.file("outside.txt", "ignored");
    const blob = await zip.generateAsync({ type: "blob" }) as Blob & { name?: string };
    blob.name = "demo.zip";

    const datapack = await loadDatapack(blob);

    expect(datapack.fileName).toBe("demo.zip");
    expect([...datapack.files.keys()]).toEqual([
      "pack.mcmeta",
      "data/demo/function/_/create.mcfunction",
    ]);
  });
});
