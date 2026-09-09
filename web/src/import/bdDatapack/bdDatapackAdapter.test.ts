import { zipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { IDENTITY_MATRIX } from "../../format/matrix";
import { serializeEmoteAnimation } from "../../format/serializer";
import { bdDatapackAdapter } from "./bdDatapackAdapter";

const encoder = new TextEncoder();
const matrix = `[${IDENTITY_MATRIX.map((value) => `${value}f`).join(",")}]`;

describe("bdDatapackAdapter", () => {
  it("detects and compiles BD Engine datapack nodes and animations", async () => {
    const input = datapack({
      "data/dance/function/_/create.mcfunction": `# Project created via BDEngine
execute as @e[tag=dance_root] run summon block_display ~ ~ ~ {Passengers:[{id:"minecraft:item_display",item:{id:"minecraft:stone",Count:1},item_display:"none",transformation:${matrix},Tags:["dance_0"]}]}`,
      "data/dance/function/k/default/keyframe_0.mcfunction": `# Project created via BDEngine
data merge entity @e[type=item_display,tag=dance_0,distance=..1,limit=1,sort=nearest] {transformation:${matrix},interpolation_duration:0}
schedule function dance:k/default/check_pause_0 0.1s`,
      "data/dance/function/k/default/keyframe_1.mcfunction": `# Project created via BDEngine
data merge entity @e[type=item_display,tag=dance_0,distance=..1,limit=1,sort=nearest] {item:{id:"minecraft:dirt"}}
data merge entity @e[type=item_display,tag=dance_0,distance=..1,limit=1,sort=nearest] {transformation:[1f,0f,0f,2f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f],interpolation_duration:2,start_interpolation:0}`,
    });

    expect((await bdDatapackAdapter.probe(input)).confidence).toBe(100);
    const project = await bdDatapackAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2" });

    expect(project).toMatchObject({ source: "bd_datapack", suggestedNamespace: "dance" });
    expect(project.nodes.display_0).toMatchObject({ skinAssignmentGroup: "display_0" });
    expect(project.nodes.display_0_variant_1).toBeUndefined();
    expect(animation.nodes.display_0).toMatchObject({ type: "item_display", item_stack_snbt: "{id:\"minecraft:stone\",count:1}" });
    expect(animation.timeline.duration).toBe("4t");
    expect(animation.timeline.tracks.display_0.position?.map((frame) => frame.time)).toEqual(["0t", "2t"]);
    expect(animation.timeline.tracks.display_0.position?.[0]).toMatchObject({ interpolation: "linear", value: [0, 0, 0] });
    expect(animation.timeline.tracks.display_0.position?.[1].value).toEqual([2, 0, 0]);
    expect(animation.timeline.tracks.display_0.nbt).toEqual([
      { time: "0t", value: "{item:{id:\"minecraft:stone\",count:1}}" },
      { time: "2t", value: "{item:{id:\"minecraft:dirt\",count:1}}" },
    ]);
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("imports each animation and reports ignored camera movement", async () => {
    const input = datapack({
      "data/project/function/_/create.mcfunction": `# Project created via BDEngine
summon block_display ~ ~ ~ {Passengers:[{id:"minecraft:block_display",block_state:{Name:"minecraft:stone"},transformation:${matrix},Tags:["project_0"]}]}`,
      "data/project/function/k/closing/keyframe_0.mcfunction": `data merge entity @e[type=block_display,tag=project_0] {transformation:${matrix},interpolation_duration:0}`,
      "data/project/function/k/opening/keyframe_0.mcfunction": `data merge entity @e[type=block_display,tag=project_0] {transformation:${matrix},interpolation_duration:0}
tp @e[type=minecraft:block_display,tag=project_camera,limit=1] ~1 ~2 ~3 0 0`,
    });

    const project = await bdDatapackAdapter.import(input);

    expect(project.animations.map((animation) => animation.id)).toEqual(["closing", "opening"]);
    expect(project.nodes.display_0).toMatchObject({ type: "block_display", blockState: { id: "minecraft:stone" } });
    expect(project.diagnostics).toEqual([expect.objectContaining({ code: "bd_datapack_camera_ignored" })]);
  });

  it("rejects unrelated ZIP files", async () => {
    const input = { name: "other.zip", bytes: zipSync({ "pack.mcmeta": encoder.encode("{}") }) };

    expect((await bdDatapackAdapter.probe(input)).confidence).toBe(0);
    await expect(bdDatapackAdapter.import(input)).rejects.toThrow("exactly one create function");
  });
});

function datapack(files: Record<string, string>) {
  return {
    name: "project.zip",
    bytes: zipSync(Object.fromEntries(Object.entries({ "pack.mcmeta": "{}", ...files }).map(([path, value]) => [path, encoder.encode(value)]))),
  };
}
