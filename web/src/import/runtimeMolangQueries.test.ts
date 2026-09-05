import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { MOD_SUPPORTED_QUERY_FUNCTION_NAMES, MOD_SUPPORTED_QUERY_VALUE_NAMES } from "./runtimeMolangQueries";

const JAVA_QUERY_CATALOG = new URL("../../../src/main/java/io/github/hanhy06/emote/molang/MolangQueryCatalog.java", import.meta.url);
const MOLANG_DOCUMENTATION = new URL("../../../docs/mkdocs/docs/developers/molang.md", import.meta.url);

describe("runtime Molang query catalog", () => {
  it("keeps the mod runtime, web preview, and documentation query names synchronized", () => {
    const runtimeNames = quotedNames(readFileSync(JAVA_QUERY_CATALOG, "utf8"));
    const previewNames = new Set([...MOD_SUPPORTED_QUERY_VALUE_NAMES, ...MOD_SUPPORTED_QUERY_FUNCTION_NAMES]);
    const documentedNames = new Set(
      [...readFileSync(MOLANG_DOCUMENTATION, "utf8").matchAll(/q\.([a-z_]+)/g)].map((match) => match[1]),
    );

    expect([...previewNames].sort()).toEqual([...runtimeNames].sort());
    expect([...documentedNames].sort()).toEqual([...runtimeNames].sort());
  });
});

function quotedNames(source: string): Set<string> {
  return new Set([...source.matchAll(/"([a-z_]+)"/g)].map((match) => match[1]));
}
