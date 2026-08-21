import { describe, expect, it } from "vitest";
import { addMetadataEntry, parseMetadataJson, renameMetadataEntry } from "./AdditionalMetadataEditor";

describe("additional metadata editing", () => {
  it("adds a non-conflicting field and preserves entry order while renaming", () => {
    const added = addMetadataEntry({ custom_1: true, license: "Apache-2.0" });
    expect(added).toEqual({ custom_1: true, license: "Apache-2.0", custom_2: "" });
    expect(Object.keys(renameMetadataEntry(added, "license", "authors"))).toEqual(["custom_1", "authors", "custom_2"]);
  });

  it("rejects reserved or duplicate keys", () => {
    expect(() => renameMetadataEntry({ credit: "A" }, "credit", "name")).toThrow("dedicated field");
    expect(() => renameMetadataEntry({ credit: "A", license: "B" }, "credit", "license")).toThrow("already exists");
  });

  it("parses arbitrary JSON values", () => {
    expect(parseMetadataJson('["A", "B"]')).toEqual(["A", "B"]);
    expect(() => parseMetadataJson("plain text")).toThrow("valid JSON");
  });
});
