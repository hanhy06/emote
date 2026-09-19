import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { MOD_SUPPORTED_QUERY_FUNCTION_NAMES, MOD_SUPPORTED_QUERY_VALUE_NAMES } from "./queryCatalog";

const MOLANG_DOCUMENTATION = new URL("../../../../docs/mkdocs/docs/developers/molang.md", import.meta.url);

describe("runtime Molang query catalog", () => {
  it("keeps the shared runtime catalog and documentation query names synchronized", () => {
    const supportedNames = new Set([...MOD_SUPPORTED_QUERY_VALUE_NAMES, ...MOD_SUPPORTED_QUERY_FUNCTION_NAMES]);
    const documentedNames = new Set(
      [...readFileSync(MOLANG_DOCUMENTATION, "utf8").matchAll(/q\.([a-z_]+)/g)].map((match) => match[1]),
    );

    expect([...documentedNames].sort()).toEqual([...supportedNames].sort());
  });
});
