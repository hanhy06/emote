import { readFile } from "node:fs/promises";
import { expect, it } from "vitest";
import { detectInputFormat } from "./formats";

it.each([
  ["bbmodel/emote.bbmodel", "geckolib_bbmodel"],
  ["aj/sit.ajblueprint", "animated_java_blueprint"],
  ["sequence.json", "emote_sequence"],
])("detects the existing %s sample with its original or misleading extension", async (path, id) => {
  const bytes = await readFile(new URL(`../../../docs/reference/${path}`, import.meta.url));
  for (const name of [path, "sample.emotecraft"]) {
    const detected = await detectInputFormat({ name, bytes });
    expect(detected).toBe(id);
  }
});

it("rejects overlapping format markers from the existing bbmodel and Sequence samples", async () => {
  const model = JSON.parse(await readFile(new URL("../../../docs/reference/bbmodel/emote.bbmodel", import.meta.url), "utf8"));
  const sequence = JSON.parse(await readFile(new URL("../../../docs/reference/sequence.json", import.meta.url), "utf8"));
  const bytes = new TextEncoder().encode(JSON.stringify({ ...model, ...sequence }));
  await expect(detectInputFormat({ name: "ambiguous.data", bytes }))
    .rejects.toMatchObject({ code: "ambiguous_input", sourcePath: "ambiguous.data" });
});
