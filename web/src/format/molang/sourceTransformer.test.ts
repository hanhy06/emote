import { describe, expect, it } from "vitest";
import { usesRuntimeMolangState } from "./runtimeAnalysis";
import { rewriteMolangIdentifiers, rewriteMolangStringLiterals } from "./sourceTransformer";

describe("Molang source transforms", () => {
  it("rewrites identifiers without touching strings, comments, or longer names", () => {
    const source = `q.anim_time + q.anim_timeline + 'q.anim_time' /* q.anim_time */`;
    expect(rewriteMolangIdentifiers(source, (value) => value === "q.anim_time" ? "v.time" : undefined))
      .toBe(`v.time + q.anim_timeline + 'q.anim_time' /* q.anim_time */`);
  });

  it("decodes and re-encodes only selected string literals", () => {
    expect(rewriteMolangStringLiterals(`f('{\\"text\\":\\"a\\"}', 'plain')`, (value) => value.startsWith("{") ? value.replace("a", "b") : undefined))
      .toBe(`f('{"text":"b"}', 'plain')`);
  });

  it("ignores runtime-looking queries in strings and comments", () => {
    expect(usesRuntimeMolangState(`'q.health'; /* q.is_moving */ q.anim_time`)).toBe(false);
    expect(usesRuntimeMolangState("q.health")).toBe(true);
  });
});
