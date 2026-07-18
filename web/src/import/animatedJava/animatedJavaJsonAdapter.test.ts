import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { serializeEmoteAnimation } from "../../format/serializer";
import { animatedJavaJsonAdapter } from "./animatedJavaJsonAdapter";

const encoder = new TextEncoder();

describe("animatedJavaJsonAdapter", () => {
  it("accepts the native Animated Java blueprint extension", () => {
    expect(animatedJavaJsonAdapter.extensions).toContain("ajblueprint");
  });

  it("imports Animated Java 1.10 project files", async () => {
    const input = nativeProject({
      elements: [{
        name: "block_display",
        position: [0, 0, 0],
        rotation: [0, 0, 0],
        scale: [0.5, 0.5, 0.5],
        visibility: true,
        block: "minecraft:stone",
        configs: { default: {}, variants: {} },
        uuid: "block",
        type: "animated_java:vanilla_block_display",
      }],
      outliner: ["block"],
      animations: [{
        name: "animation",
        loop: "once",
        length: 0.1,
        loop_delay: "0",
        animators: {
          block: {
            name: "block_display",
            type: "animated_java:vanilla_block_display",
            keyframes: [
              projectFrame("position", 0, ["0", "0", "0"]),
              projectFrame("position", 0.1, ["8", "0", "0"]),
              projectFrame("scale", 0, ["1", "1", "1"]),
              projectFrame("scale", 0.1, ["2", "1", "1"]),
            ],
          },
        },
      }],
    });

    expect(animatedJavaJsonAdapter.probe(input)).toEqual({
      confidence: 100,
      reason: "matches an Animated Java blueprint project",
    });
    const project = await animatedJavaJsonAdapter.import(input);
    const node = project.nodes.block;
    expect(node.type).toBe("block_display");
    expect(node.type === "block_display" && node.blockStateSnbt).toContain("minecraft:stone");
    expect(project.animations[0].tracks.block.transforms[2].matrix[3]).toBeCloseTo(0.5);
    expect(project.animations[0].tracks.block.transforms[2].matrix[0]).toBeCloseTo(1);
  });

  it("interpolates unsorted native project keyframes across ticks", async () => {
    const element = {
      name: "block_display",
      position: [0, 0, 0],
      rotation: [0, 0, 0],
      scale: [1, 1, 1],
      visibility: true,
      block: "minecraft:stone",
      configs: { default: {}, variants: {} },
      uuid: "block",
      type: "animated_java:vanilla_block_display",
    };
    const input = nativeProject({
      elements: [element],
      outliner: ["block"],
      animations: [{
        name: "animation",
        loop: "once",
        length: 0.15,
        loop_delay: "0",
        animators: {
          block: {
            name: element.name,
            type: element.type,
            keyframes: [
              projectFrame("position", 0.15, ["12", "0", "0"]),
              projectFrame("position", 0, ["0", "0", "0"]),
              projectFrame("position", 0.1, ["8", "0", "0"]),
            ],
          },
        },
      }],
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const translations = project.animations[0].tracks.block.transforms.map((frame) => frame.matrix[3]);

    expect(translations).toEqual([0, 0.25, 0.5, 0.75]);
  });

  it("imports baked display tracks and locator anchors", async () => {
    const input = blueprint({
      item: {
        type: "item_display",
        default_transformation: { matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5, 1, 0, 1] },
        display_properties: { item: "minecraft:diamond_sword[damage=3]", item_display: "fixed" },
      },
      effect: { type: "locator", default_transformation: { matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 2, 0, 1] } },
    }, {
      wave: {
        loop_mode: { type: "loop", loop_delay: "0.1" },
        blend_weight: "1",
        start_delay: "0",
        length: 0.1,
        node_keyframes: {
          item: {
            position: {
              "0.0": baked(["0.5", "1", "0"]),
              "0.05": baked(["1", "1", "0"]),
            },
            rotation: { "0.0": baked(["0", "180", "0"]), "0.05": baked(["0", "180", "0"]) },
            scale: { "0.0": baked(["1", "1", "1"]), "0.05": baked(["1", "1", "1"]) },
          },
        },
      },
    });

    expect((await animatedJavaJsonAdapter.probe(input)).confidence).toBe(100);
    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "animated_java" });
    expect(animation.nodes.effect.type).toBe("anchor");
    expect(animation.nodes.item.default_matrix[3]).toBe(0.5);
    expect(animation.timeline.duration_ticks).toBe(2);
    expect(animation.timeline.loop_delay_ticks).toBe(2);
    expect(animation.timeline.keyframes.map((keyframe) => keyframe.tick)).toEqual([0, 1]);
    expect(animation.timeline.keyframes.map((keyframe) => keyframe.node_transforms?.item.interpolation_duration_ticks)).toEqual([0, 1]);
    expect(animation.nodes.item.type === "item_display" && animation.nodes.item.item_stack_snbt).toContain('"minecraft:damage":3');
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("creates resource-pack artifacts for bone models", async () => {
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:rig" },
      textures: { skin: { type: "custom", base64_string: "iVBORw0KGgo=" } },
      nodes: {
        body: {
          type: "bone",
          elements: [{
            from: [0, 0, 0], to: [1, 1, 1], rotation: [0, 0, 0],
            faces: { north: { uv: [0, 0, 16, 16], texture_provider: { type: "texture", texture: "skin" } } },
          }],
        },
      },
      animations: { idle: { loop_mode: { type: "once" }, length: 0.05 } },
    });
    const project = await animatedJavaJsonAdapter.import(input);
    expect([...project.artifacts.keys()]).toEqual([
      "assets/demo/textures/item/rig/skin.png",
      "assets/demo/models/item/rig/body.json",
      "assets/demo/items/rig/body.json",
    ]);
  });

  it("bakes a numeric blend weight into node transforms", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      blended: {
        loop_mode: { type: "once" },
        blend_weight: "0.5",
        length: 0.05,
        node_keyframes: {
          item: {
            position: { "0.0": baked(["2", "0", "0"]) },
            rotation: { "0.0": baked(["0", "180", "0"]) },
            scale: { "0.0": baked(["2", "2", "2"]) },
          },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "blend" });
    const matrix = animation.timeline.keyframes[0].node_transforms?.item.matrix;

    expect(matrix?.[3]).toBeCloseTo(1);
    expect(matrix?.[0]).toBeCloseTo(1.5);
    expect(matrix?.[5]).toBeCloseTo(1.5);
    expect(matrix?.[10]).toBeCloseTo(1.5);
  });

  it("bakes raw Animated Java easing at every tick", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      raw: {
        loop_mode: { type: "once" },
        length: 0.1,
        node_keyframes: { item: { position: {
          "0.0": { value: ["0", "0", "0"], interpolation: { type: "linear", easing: "linear" } },
          "0.1": { value: ["2", "0", "0"], interpolation: { type: "linear", easing: "ease_in_quad" } },
        } } },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "easing" });

    expect(animation.timeline.keyframes.map((keyframe) => keyframe.tick)).toEqual([0, 1, 2]);
    expect(animation.timeline.keyframes[1].node_transforms?.item.matrix[3]).toBeCloseTo(0.5);
  });

  it("bakes pre/post keyframes and time-based Molang expressions", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      raw: {
        loop_mode: { type: "once" },
        length: 0.1,
        node_keyframes: { item: { position: {
          "0.0": {
            value: ["query.anim_time * 20", "0", "0"],
            post: ["2", "0", "0"],
            interpolation: { type: "linear", easing: "linear" },
          },
          "0.1": { value: ["4", "0", "0"], interpolation: { type: "linear", easing: "linear" } },
        } } },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "molang" });
    const positions = animation.timeline.keyframes.map((keyframe) => keyframe.node_transforms?.item.matrix[3]);

    expect(positions).toEqual([0, 3, 4]);
  });

  it("bakes bezier and catmull-rom interpolation", async () => {
    const bezier = {
      type: "bezier",
      left_handle_time: [-0.03, -0.03, -0.03],
      left_handle_value: [0, 0, 0],
      right_handle_time: [0.03, 0.03, 0.03],
      right_handle_value: [1, 0, 0],
    };
    const input = blueprint({ item: { type: "item_display" } }, {
      curves: {
        loop_mode: { type: "once" },
        length: 0.2,
        node_keyframes: { item: {
          position: {
            "0.0": { value: ["0", "0", "0"], interpolation: bezier },
            "0.1": { value: ["2", "0", "0"], interpolation: { ...bezier, left_handle_value: [1, 0, 0] } },
          },
          scale: {
            "0.0": { value: ["1", "1", "1"], interpolation: { type: "catmullrom" } },
            "0.1": { value: ["2", "2", "2"], interpolation: { type: "catmullrom" } },
            "0.2": { value: ["1", "1", "1"], interpolation: { type: "catmullrom" } },
          },
        } },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "curves" });

    expect(animation.timeline.keyframes).toHaveLength(5);
    expect(animation.timeline.keyframes.every((keyframe) => keyframe.node_transforms?.item.matrix.every(Number.isFinite))).toBe(true);
  });

  it("reports the path of malformed blueprint input", async () => {
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:test" },
      nodes: { item: { type: "item_display" } },
      animations: { idle: { loop_mode: null, length: 1 } },
    });

    await expect(animatedJavaJsonAdapter.import(input)).rejects.toThrow("animations.idle.loop_mode must be an object");
  });
});

function blueprint(nodes: Record<string, unknown>, animations: Record<string, unknown>) {
  return rawBlueprint({ format_version: 1, settings: { id: "demo:test" }, nodes, animations });
}

function rawBlueprint(value: unknown) {
  return { name: "blueprint.json", bytes: encoder.encode(JSON.stringify(value)) };
}

function nativeProject(value: { elements: unknown[]; outliner: unknown[]; animations: unknown[] }) {
  return {
    name: "unnamed.ajblueprint",
    bytes: encoder.encode(JSON.stringify({
      meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
      resolution: { width: 16, height: 16 },
      blueprint_settings: { enable_plugin_mode: true },
      groups: [],
      textures: [],
      variants: null,
      ...value,
    })),
  };
}

function projectFrame(channel: string, time: number, values: [string, string, string]) {
  return {
    channel,
    time,
    data_points: [{ x: values[0], y: values[1], z: values[2] }],
    interpolation: "linear",
    easing: "linear",
  };
}

function baked(value: string[]) {
  return { value, interpolation: { type: "linear", easing: "linear" } };
}
