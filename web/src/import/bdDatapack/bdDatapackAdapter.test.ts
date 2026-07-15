import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { serializeEmoteAnimation } from "../../format/serializer";
import { bdDatapackAdapter } from "./bdDatapackAdapter";

describe("bdDatapackAdapter", () => {
  it("converts the running cry emote into schema version 1", async () => {
    const bytes = new Uint8Array(await readFile(new URL("../../../../docs/example/emote.cry.zip", import.meta.url)));
    const input = { name: "emote.cry.zip", bytes };

    expect((await bdDatapackAdapter.probe(input)).confidence).toBe(100);
    const project = await bdDatapackAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "cry" });

    expect(Object.keys(animation.nodes)).toHaveLength(11);
    expect(animation.timeline.duration_ticks).toBe(38);
    expect(animation.timeline.loop).toBe("loop");
    expect(animation.timeline.keyframes.map((keyframe) => keyframe.tick)).toEqual(Array.from({ length: 19 }, (_, index) => index * 2));
    expect(Object.keys(animation.timeline.keyframes[0].node_transforms ?? {})).toHaveLength(11);
    expect(Object.keys(animation.timeline.keyframes[1].node_transforms ?? {})).toHaveLength(3);
    expect(animation.timeline.events?.timeline).toHaveLength(19);
    expect(animation.timeline.events?.timeline?.[0].origin).toEqual({ type: "root", offset: [0, 1.3, -0.15] });
    expect(animation.timeline.events?.timeline?.[0].commands[0]).toContain("particle minecraft:falling_water ~ ~ ~");
    expect(animation.nodes.cry_7.type === "item_display" && animation.nodes.cry_7.skin).toEqual({ part: "right_arm", order: 0 });
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });
});
