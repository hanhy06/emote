import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { serializeEmoteAnimation } from "../../format/serializer";
import { geckoLibBbmodelAdapter } from "./geckoLibBbmodelAdapter";

const encoder = new TextEncoder();

describe("geckoLibBbmodelAdapter", () => {
  it("detects only GeckoLib Blockbench projects", () => {
    expect(geckoLibBbmodelAdapter.probe(input(project()))).toEqual({
      confidence: 100,
      reason: "matches a GeckoLib Blockbench project",
    });
    expect(geckoLibBbmodelAdapter.probe(input({ meta: { model_format: "java_block" } }))).toEqual({
      confidence: 0,
      reason: "not a GeckoLib Blockbench project",
    });
  });

  it("imports embedded geometry, nested bones, and baked transform tracks", async () => {
    const imported = await geckoLibBbmodelAdapter.import(input(project()));

    expect(imported.source).toBe("geckolib_bbmodel");
    expect(imported.suggestedNamespace).toBe("demo");
    expect(Object.keys(imported.nodes)).toEqual(["root", "child"]);
    expect(imported.nodes.root.type).toBe("item_display");
    expect(imported.nodes.child.type).toBe("anchor");
    expect([...imported.artifacts.keys()]).toEqual([
      "assets/demo/textures/item/test_model/texture.png",
      "assets/demo/models/item/test_model/root.json",
      "assets/demo/items/test_model/root.json",
    ]);

    const model = JSON.parse(new TextDecoder().decode(imported.artifacts.get("assets/demo/models/item/test_model/root.json"))) as {
      elements: { from: number[]; to: number[] }[];
    };
    expect(model.elements[0].from).toEqual([8, 8, 8]);
    expect(model.elements[0].to).toEqual([12, 12, 12]);

    const animation = imported.animations[0];
    expect(animation.durationTicks).toBe(2);
    expect(animation.loop).toBe("loop");
    expect(animation.loopDelayTicks).toBe(1);
    expect(animation.tracks.root.transforms.map((frame) => frame.tick)).toEqual([0, 1, 2]);
    expect(animation.tracks.root.transforms[2].matrix[3]).toBeCloseTo(1);
    expect(animation.tracks.child.transforms[2].matrix[3]).toBeCloseTo(1);
    expect(animation.tracks.child.transforms[2].matrix[7]).toBeCloseTo(1);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("rejects interpolation that would otherwise be silently lost", async () => {
    const value = project();
    value.animations[0].animators.root.keyframes[1].interpolation = "catmullrom";

    await expect(geckoLibBbmodelAdapter.import(input(value))).rejects.toThrow("only linear and step are supported");
  });

  it("requires the texture to be embedded", async () => {
    const value = project();
    (value.textures[0] as { source?: string }).source = undefined;

    await expect(geckoLibBbmodelAdapter.import(input(value))).rejects.toThrow("must be embedded");
  });
});

function project() {
  return {
    meta: { format_version: "5.0", model_format: "geckolib_model" },
    name: "Test Model",
    geckolib_modid: "demo",
    resolution: { width: 16, height: 16 },
    elements: [{
      uuid: "cube",
      name: "Cube",
      type: "cube",
      from: [0, 0, 0],
      to: [4, 4, 4],
      origin: [0, 0, 0],
      rotation: [0, 0, 0],
      faces: {
        north: { uv: [0, 0, 4, 4], texture: 0 },
        south: { uv: [0, 0, 4, 4], texture: 0 },
      },
    }],
    groups: [
      { uuid: "root", name: "Root", origin: [0, 0, 0], rotation: [0, 0, 0] },
      { uuid: "child", name: "Child", origin: [0, 16, 0], rotation: [0, 0, 0] },
    ],
    outliner: [{
      uuid: "root",
      name: "Root",
      origin: [0, 0, 0],
      rotation: [0, 0, 0],
      children: ["cube", {
        uuid: "child",
        name: "Child",
        origin: [0, 16, 0],
        rotation: [0, 0, 0],
        children: [],
      }],
    }],
    textures: [{
      id: "0",
      name: "texture.png",
      source: "data:image/png;base64,iVBORw0KGgo=",
    }],
    animations: [{
      uuid: "animation",
      name: "animation.test.wave",
      length: 0.1,
      loop: "loop",
      loop_delay: 0.05,
      animators: {
        root: {
          name: "Root",
          keyframes: [
            { channel: "position", time: 0, interpolation: "linear", data_points: [{ x: 0, y: 0, z: 0 }] },
            { channel: "position", time: 0.1, interpolation: "linear", data_points: [{ x: 16, y: 0, z: 0 }] },
          ],
        },
      },
    }],
  };
}

function input(value: unknown) {
  return { name: "test.bbmodel", bytes: encoder.encode(JSON.stringify(value)) };
}
