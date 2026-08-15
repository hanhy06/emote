import { describe, expect, it } from "vitest";
import { cachedInputPromise, cachedInputValue, parseInputJson, parseInputJsonc } from "./inputCache";

describe("input cache", () => {
  it("reuses synchronous and asynchronous preparation for one input", async () => {
    const input = { name: "test.json", bytes: new TextEncoder().encode('{"value":1}') };
    let valueLoads = 0;
    let promiseLoads = 0;

    expect(cachedInputValue(input, "value", () => ++valueLoads)).toBe(1);
    expect(cachedInputValue(input, "value", () => ++valueLoads)).toBe(1);
    expect(await cachedInputPromise(input, "promise", async () => ++promiseLoads)).toBe(1);
    expect(await cachedInputPromise(input, "promise", async () => ++promiseLoads)).toBe(1);
    expect(parseInputJson(input)).toBe(parseInputJson(input));
    expect({ valueLoads, promiseLoads }).toEqual({ valueLoads: 1, promiseLoads: 1 });
  });

  it("parses and caches Bedrock-style JSON with comments and trailing commas", () => {
    const input = {
      name: "animation.json",
      bytes: new TextEncoder().encode(`{
        // Bedrock resource packs commonly contain comments.
        "format_version": "1.8.0",
        "animations": {},
      }`),
    };

    expect(parseInputJsonc(input)).toEqual({ format_version: "1.8.0", animations: {} });
    expect(parseInputJsonc(input)).toBe(parseInputJsonc(input));
  });

  it("reports the first JSONC syntax error", () => {
    const input = { name: "broken.json", bytes: new TextEncoder().encode('{"animations": ]}') };
    expect(() => parseInputJsonc(input)).toThrow(/Invalid JSONC at offset/);
  });
});
