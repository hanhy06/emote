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
    expect(imported.nodes.root.defaultMatrix).toEqual([
      0.5, 0, 0, 0.125,
      0, 0.5, 0, 0.25,
      0, 0, 0.5, 0.125,
      0, 0, 0, 1,
    ]);
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.itemStackSnbt).toContain("minecraft:player_head");
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.skin).toEqual({ part: "body", order: 0 });
    expect([...imported.resources.keys()]).toEqual([
      "assets/demo/textures/item/test_model/texture.png",
      "assets/demo/models/item/test_model/root.json",
      "assets/demo/items/test_model/root.json",
    ]);

    const model = JSON.parse(new TextDecoder().decode(imported.resources.get("assets/demo/models/item/test_model/root.json"))) as {
      elements: { from: number[]; to: number[] }[];
    };
    expect(model.elements[0].from).toEqual([8, 8, 8]);
    expect(model.elements[0].to).toEqual([12, 12, 12]);

    const animation = imported.animations[0];
    expect(animation.durationTicks).toBe(2);
    expect(animation.loop).toBe("loop");
    expect(animation.loopDelayTicks).toBe(1);
    expect(animation.tracks.root.transforms.map((frame) => frame.tick)).toEqual([0, 1, 2]);
    expect(animation.tracks.root.transforms[2].matrix[3]).toBeCloseTo(1.125);
    expect(animation.tracks.child.transforms[2].matrix[3]).toBeCloseTo(1);
    expect(animation.tracks.child.transforms[2].matrix[7]).toBeCloseTo(1);

    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2" });
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("splits cubes in the same bone into independently assignable nodes", async () => {
    const value = project();
    value.elements.push({
      ...value.elements[0],
      uuid: "second_cube",
      name: "Second Cube",
      from: [4, 0, 0],
      to: [8, 4, 4],
    });
    value.outliner[0].children.splice(1, 0, "second_cube");

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["root", "root_second_cube", "child"]);
    expect(imported.nodes.root_second_cube.type).toBe("item_display");
    expect(imported.nodes.root.type === "item_display" && imported.nodes.root.skin).toEqual({ part: "body", order: 0 });
    expect(imported.nodes.root_second_cube.type === "item_display" && imported.nodes.root_second_cube.skin).toEqual({ part: "body", order: 1 });
    expect(imported.animations[0].tracks.root_second_cube.transforms).toHaveLength(3);
    expect(imported.animations[0].tracks.root_second_cube.transforms[2].matrix[3]).toBeCloseTo(1.375);
    expect([...imported.resources.keys()]).toContain("assets/demo/models/item/test_model/root_second_cube.json");
  });

  it("removes duplicate skin-layer cubes from the emote while preserving their resource models", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.outliner[0].name = "Right Arm";
    value.elements.push(Object.assign({
      ...value.elements[0],
      uuid: "arm_layer",
      name: "Right Arm Layer",
    }, { inflate: 0.25 }));
    value.outliner[0].children.splice(1, 0, "arm_layer");

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["right_arm", "child"]);
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.skin).toEqual({ part: "right_arm", order: 0 });
    expect([...imported.resources.keys()]).toContain("assets/demo/models/item/test_model/right_arm_right_arm_layer.json");
  });

  it("splits tall body-part cubes into ordered upper and lower player heads", async () => {
    const value = project();
    value.groups[0].name = "Right Arm";
    value.outliner[0].name = "Right Arm";
    value.elements[0].name = "Right Arm";
    value.elements[0].from = [4, 12, -2];
    value.elements[0].to = [8, 24, 2];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(Object.keys(imported.nodes)).toEqual(["right_arm", "right_arm_right_arm_lower", "child"]);
    expect(imported.nodes.right_arm.type === "item_display" && imported.nodes.right_arm.skin).toEqual({ part: "right_arm", order: 0 });
    expect(imported.nodes.right_arm_right_arm_lower.type === "item_display" && imported.nodes.right_arm_right_arm_lower.skin)
      .toEqual({ part: "right_arm", order: 1 });
    expect(imported.nodes.right_arm.defaultMatrix[5]).toBeCloseTo(0.5);
    expect(imported.nodes.right_arm_right_arm_lower.defaultMatrix[5]).toBeCloseTo(1);
    expect([...imported.resources.keys()].filter((path) => path.endsWith(".json"))).toHaveLength(2);
  });

  it("reconnects stale animator UUIDs by a unique normalized bone name", async () => {
    const value = project();
    const rootAnimator = value.animations[0].animators.root;
    value.animations[0].animators = {
      stale_root: { ...rootAnimator, name: "r_o-o t" },
    } as unknown as typeof value.animations[0]["animators"];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.animations[0].tracks.root.transforms[2].matrix[3]).toBeCloseTo(1.125);
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

  it("imports textureless animation-only bone projects", async () => {
    const value = project();
    value.elements = [];
    value.textures = [];
    value.outliner[0].children = [];

    const imported = await geckoLibBbmodelAdapter.import(input(value));

    expect(imported.nodes.root.type).toBe("anchor");
    expect(imported.resources.size).toBe(0);
    expect(imported.resourceMinecraftVersion).toBeUndefined();
    expect(imported.animations[0].tracks.root.transforms).toHaveLength(3);
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
