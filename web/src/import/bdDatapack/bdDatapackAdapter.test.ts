import JSZip from "jszip";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { serializeEmoteAnimation } from "../../format/serializer";
import { bdDatapackAdapter } from "./bdDatapackAdapter";

describe("bdDatapackAdapter", () => {
  it("converts a BD datapack into schema version 1", async () => {
    const bytes = await createDatapack();
    const input = { name: "emote.cry.zip", bytes };

    expect((await bdDatapackAdapter.probe(input)).confidence).toBe(100);
    const project = await bdDatapackAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "cry" });

    expect(Object.keys(animation.nodes)).toHaveLength(1);
    expect(animation.timeline.duration_ticks).toBe(1);
    expect(animation.timeline.loop).toBe("loop");
    expect(animation.timeline.keyframes.map((keyframe) => keyframe.tick)).toEqual([0]);
    expect(animation.timeline.keyframes[0].node_transforms?.cry_0?.matrix).toHaveLength(16);
    expect(animation.nodes.cry_0.type === "item_display" && animation.nodes.cry_0.skin).toEqual({ part: "head", order: 0 });
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });
});

async function createDatapack(): Promise<Uint8Array> {
  const identity = "1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f";
  const zip = new JSZip();
  zip.file("pack.mcmeta", JSON.stringify({ pack: { pack_format: 1, description: "test" } }));
  zip.file(
    "data/cry/function/_/create.mcfunction",
    `# created via BDEngine\nsummon item_display ~ ~ ~ {id:"minecraft:item_display",Tags:["cry_0"],item:{id:"minecraft:player_head",Count:1,components:{"minecraft:custom_data":{name:"emote:head"}}},item_display:"none",transformation:[${identity}]}`,
  );
  zip.file(
    "data/cry/function/k/default/keyframe_0.mcfunction",
    `data merge entity @e[tag=cry_0,limit=1] {transformation:[${identity}],interpolation_duration:0}\nschedule function cry:k/default/keyframe_0 1t`,
  );
  zip.file("data/cry/emote.json", JSON.stringify({
    name: "Cry",
    description: "Test emote.",
    command_name: "cry",
    hide_player: true,
    entrypoint: "default/play_anim_loop",
  }));
  return zip.generateAsync({ type: "uint8array" });
}
