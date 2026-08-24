import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { serializeEmoteAnimation } from "../../format/serializer";
import { MAX_ANIMATION_DURATION_TICKS, TICKS_PER_SECOND } from "../../format/time";
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

  it("keeps unknown native Animated Java Molang as a warned Create pose", async () => {
    const input = nativeProject({
      elements: [{
        name: "block_display",
        position: [0, 0, 0],
        rotation: [0, 0, 0],
        scale: [1, 1, 1],
        visibility: true,
        block: "minecraft:stone",
        configs: { default: {}, variants: {} },
        uuid: "block",
        type: "animated_java:vanilla_block_display",
      }],
      outliner: ["block"],
      animations: [{
        name: "runtime",
        loop: "once",
        length: 0.1,
        loop_delay: "0",
        animators: { block: { keyframes: [projectFrame("position", 0, ["q.ground_speed", "0", "0"])] } },
      }],
    });

    const imported = await animatedJavaJsonAdapter.import(input);

    expect(imported.animations[0].availability).toMatchObject({ preview: "create_pose", exportable: true });
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({
      code: "animated_java_animation_molang_unavailable",
      sourcePath: "animations[0].animators.block.position[0]",
    }));
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "native_runtime" });
    expect(compiled.timeline.tracks.aj_block_z.position?.[0].value?.[0]).toBe("((q.ground_speed) * 0.0625)");
    expect(compiled.nodes.block.type).toBe("block_display");
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
  });

  it("imports Animated Java 1.10 native cube rigs", async () => {
    const cube = {
      uuid: "arm_cube",
      name: "Right Arm",
      type: "cube",
      from: [4, 12, -2],
      to: [8, 24, 2],
      origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 4, 12], texture: "skin_uuid" } },
    };
    const input = {
      name: "unnamed.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 64, height: 64 },
        elements: [cube, { ...cube, uuid: "arm_layer", name: "Right Arm Layer", inflate: 0.25 }],
        groups: [
          { uuid: "waist_uuid", name: "waist", origin: [0, 12, 0], rotation: [0, 0, 0] },
          { uuid: "arm_uuid", name: "right_arm", origin: [5, 22, 0], rotation: [10, 0, 0] },
        ],
        outliner: [{ uuid: "waist_uuid", children: [{ uuid: "arm_uuid", children: ["arm_cube", "arm_layer"] }] }],
        textures: [{ uuid: "skin_uuid", source: "data:image/png;base64,iVBORw0KGgo=" }],
        animations: [{
          name: "wave",
          loop: "once",
          length: 0.05,
          animators: {
            arm_uuid: { name: "right_arm", type: "bone", keyframes: [projectFrame("rotation", 0, ["0", "0", "0"])] },
          },
        }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);

    expect(project.source).toBe("animated_java_json");
    expect(project.sourceName).toBe("unnamed.ajblueprint");
    expect(project.nodes.right_arm.type === "item_display" && project.nodes.right_arm.suggestedSkin)
      .toEqual({ part: "right_arm", order: 0 });
    expect(project.nodes.right_arm_right_arm_lower.type === "item_display" && project.nodes.right_arm_right_arm_lower.suggestedSkin)
      .toEqual({ part: "right_arm", order: 1 });
    expect(project.animations[0].tracks.right_arm_right_arm_lower.transforms)
      .toEqual(project.animations[0].tracks.right_arm.transforms);
  });

  it("rejects native projects longer than ten minutes before baking transforms", async () => {
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
      outliner: [element.uuid],
      animations: [{
        name: "too_long",
        loop: "once",
        length: (MAX_ANIMATION_DURATION_TICKS + 1) / TICKS_PER_SECOND,
        loop_delay: "0",
        animators: {},
      }],
    });

    await expect(animatedJavaJsonAdapter.import(input)).rejects.toThrow("between 1 and 12000 ticks");
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
    expect(animation.nodes.item.transform.position[0]).toBe(0.5);
    expect(animation.timeline.duration).toBe("2t");
    expect(animation.settings.playback.loop_delay).toBe("2t");
    expect(animation.timeline.tracks.item.position?.map((keyframe) => keyframe.time)).toEqual(["0t", "1t"]);
    expect(animation.timeline.tracks.item.position?.map((keyframe) => keyframe.interpolation)).toEqual(["linear", undefined]);
    expect(animation.nodes.item.type === "item_display" && animation.nodes.item.item_stack_snbt).toContain('"minecraft:damage":3');
    expect(() => serializeEmoteAnimation(animation)).not.toThrow();
  });

  it("preserves Animated Java display properties as entity NBT", async () => {
    const input = blueprint({
      label: {
        type: "text_display",
        display_properties: {
          text: '{"text":"Label"}',
          billboard: "vertical",
          custom_brightness: 12,
          glow_color_override: 0x123456,
          is_glowing: true,
          shadow_radius: 0.5,
          shadow_strength: 0.75,
          alignment: "left",
          background_color: 0x40112233,
          is_default_background: false,
          is_see_through: true,
          is_shadowed: true,
          line_width: 160,
          text_opacity: -16,
        },
      },
    }, { idle: { loop_mode: { type: "once" }, length: 0.05 } });

    const project = await animatedJavaJsonAdapter.import(input);
    const node = project.nodes.label;

    expect(node.type).toBe("text_display");
    expect(node.type !== "anchor" && node.entityNbt).toBe(
      '{billboard:"vertical",shadow_radius:0.5,shadow_strength:0.75,glow_color_override:1193046,Glowing:1b,brightness:{sky:12,block:12},alignment:"left",background:1074864691,line_width:160,text_opacity:-16b,default_background:0b,see_through:1b,shadow:1b}',
    );
  });

  it("creates resource-pack files for bone models", async () => {
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
    expect([...project.resources.keys()]).toEqual([
      "assets/demo/textures/item/rig/skin.png",
      "assets/demo/models/item/rig/body.json",
      "assets/demo/items/rig/body.json",
    ]);
  });

  it("splits Animated Java bones into independently assignable player-head cubes", async () => {
    const face = { uv: [0, 0, 4, 12], texture_provider: { type: "texture", texture: "skin" } };
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:rig" },
      textures: { skin: { type: "custom", base64_string: "iVBORw0KGgo=" } },
      nodes: {
        right_arm: {
          type: "bone",
          elements: [
            { from: [8, 0, 6], to: [12, 12, 10], rotation: [0, 0, 0], faces: { north: face } },
            { from: [0, 0, 0], to: [2, 2, 2], rotation: { angle: 22.5, axis: "z", origin: [1, 1, 1] }, faces: { north: face } },
          ],
        },
      },
      animations: {
        wave: {
          loop_mode: { type: "once" },
          length: 0.05,
          node_keyframes: {
            right_arm: { position: { "0.0": baked(["0", "0", "0"]), "0.05": baked(["1", "0", "0"]) } },
          },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);

    expect(Object.keys(project.nodes)).toEqual(["right_arm", "right_arm_2", "right_arm_3"]);
    expect(project.nodes.right_arm.type === "item_display" && project.nodes.right_arm.suggestedSkin)
      .toEqual({ part: "right_arm", order: 0 });
    expect(project.nodes.right_arm_2.type === "item_display" && project.nodes.right_arm_2.suggestedSkin)
      .toEqual({ part: "right_arm", order: 1 });
    expect(project.nodes.right_arm_3.type === "item_display" && project.nodes.right_arm_3.suggestedSkin).toBeUndefined();
    expect(project.nodes.right_arm_3.type === "item_display" && project.nodes.right_arm_3.playerHeadConversion?.matrix.every(Number.isFinite)).toBe(true);
    expect(project.nodes.right_arm.type === "item_display" && project.nodes.right_arm.playerHeadConversion?.matrix[5]).toBeCloseTo(0.5);
    expect(project.nodes.right_arm_2.type === "item_display" && project.nodes.right_arm_2.playerHeadConversion?.matrix[5]).toBeCloseTo(1);
    expect(project.animations[0].tracks.right_arm_2.transforms).toEqual(project.animations[0].tracks.right_arm.transforms);
    expect(project.animations[0].tracks.right_arm_3.transforms).toEqual(project.animations[0].tracks.right_arm.transforms);
    expect([...project.resources.keys()].filter((path) => path.endsWith(".json"))).toHaveLength(6);
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
    const position = animation.timeline.tracks.item.position?.[0].value;
    const scale = animation.timeline.tracks.item.scale?.[0].value;

    expect(position?.[0]).toBeCloseTo(1);
    expect(scale).toEqual([1.5, 1.5, 1.5]);
  });

  it("preserves non-canonical decimal timestamps after a start delay", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      delayed: {
        loop_mode: { type: "once" },
        start_delay: "0.1",
        length: 0.1,
        node_keyframes: {
          item: { position: { "0.050": baked(["2", "0", "0"]) } },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "delayed" });
    const transforms = animation.timeline.tracks.item.position!;

    expect(transforms.map((keyframe) => keyframe.time)).toEqual(["0t", "2t", "3t"]);
    expect(transforms.map((keyframe) => keyframe.interpolation)).toEqual(["step", "linear", undefined]);
    expect(transforms.map((keyframe) => keyframe.value?.[0])).toEqual([0, 0, 2]);
  });

  it("applies a time-zero keyframe only after the start delay", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      delayed: {
        loop_mode: { type: "once" },
        start_delay: "0.1",
        length: 0.1,
        node_keyframes: {
          item: { position: { "0.0": baked(["2", "0", "0"]) } },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "delayed" });
    const keyframes = animation.timeline.tracks.item.position!;

    expect(keyframes.map((keyframe) => keyframe.time)).toEqual(["0t", "2t"]);
    expect(keyframes[0].interpolation).toBe("step");
    expect(keyframes[1].value?.[0]).toBeCloseTo(2);
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

    expect(animation.timeline.tracks.item.position?.map((keyframe) => keyframe.time)).toEqual(["0t", "1t", "2t"]);
    expect(animation.timeline.tracks.item.position?.[1].value?.[0]).toBeCloseTo(0.5);
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
    const positions = animation.timeline.tracks.item.position?.map((keyframe) => keyframe.value?.[0]);

    expect(positions).toEqual([0, 3, 4]);
  });

  it("keeps unknown Animated Java Molang as a warned Create pose", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      runtime: {
        loop_mode: { type: "once" },
        length: 0.1,
        node_keyframes: { item: { position: {
          "0.0": { value: ["v.runtime_speed", "0", "0"], interpolation: { type: "linear", easing: "linear" } },
        } } },
      },
    });

    const imported = await animatedJavaJsonAdapter.import(input);

    expect(imported.animations[0]).toMatchObject({
      tracks: {},
      availability: { preview: "create_pose", exportable: true },
    });
    expect(imported.diagnostics).toContainEqual(expect.objectContaining({
      code: "animated_java_animation_molang_unavailable",
      sourcePath: "runtime/item/position",
    }));
    const [compiled] = compileImportedProject(imported, { minecraftVersion: "26.2", namespace: "runtime" });
    expect(compiled.timeline.tracks.aj_item_y.position?.[0].value?.[0]).toBe("v.runtime_speed");
    expect(compiled.nodes.item.type).toBe("item_display");
    expect(() => serializeEmoteAnimation(compiled)).not.toThrow();
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

    expect(animation.timeline.tracks.item.position).toHaveLength(5);
    expect(animation.timeline.tracks.item.position?.every((keyframe) => keyframe.value?.every((value) => typeof value === "number" && Number.isFinite(value)))).toBe(true);
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
