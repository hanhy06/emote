import { describe, expect, it } from "vitest";
import { cachedInputPromise, cachedInputValue, parseInputJson } from "./inputCache";

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
});
