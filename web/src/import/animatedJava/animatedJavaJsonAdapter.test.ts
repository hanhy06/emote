import { describe, expect, it, vi } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { serializeEmoteAnimation } from "../../format/serializer";
import { animatedJavaJsonAdapter } from "./animatedJavaJsonAdapter";

const encoder = new TextEncoder();

describe("animatedJavaJsonAdapter", () => {
  it("accepts Animated Java projects and Plugin Blueprint JSON files", async () => {
    expect(animatedJavaJsonAdapter.extensions).toEqual(["ajblueprint", "json"]);
    expect(animatedJavaJsonAdapter.label).toBe("Animated Java project");
    expect((await animatedJavaJsonAdapter.probe(rawBlueprint({
      meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
      resolution: { width: 16, height: 16 },
      elements: [],
      groups: [],
      outliner: [],
      textures: [],
      animations: [],
    }))).confidence).toBe(100);
  });

  it("imports native Animated Java project files", async () => {
    const input = nativeProject({
      elements: [{
        name: "block_display",
        position: [0, 0, -16],
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
            ],
          },
        },
      }],
    });

    const project = await animatedJavaJsonAdapter.import(input);

    expect(project.sourceName).toBe("unnamed.ajblueprint");
    expect(project.nodes.block.type).toBe("block_display");
    expect(project.animations[0].tracks.block.transforms[2].matrix[3]).toBeCloseTo(0.5);
    expect(project.animations[0].tracks.block.transforms[2].matrix[0]).toBeCloseTo(0.5);
    expect(project.animations[0].tracks.block.transforms[2].matrix[11]).toBeCloseTo(-1);
  });

  it("imports native Animated Java cube rigs", async () => {
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
      name: "humanoid.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        name: "Humanoid",
        resolution: { width: 64, height: 64 },
        elements: [cube],
        outliner: [{ uuid: "arm_uuid", name: "right_arm", origin: [5, 22, 0], children: ["arm_cube"] }],
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

    expect(project.sourceName).toBe("humanoid.ajblueprint");
    expect(Object.keys(project.nodes).length).toBeGreaterThan(0);
    expect(project.animations).toHaveLength(1);
    expect(project.nodes.right_arm.defaultMatrix[3]).toBeCloseTo(-0.29296875);
    expect(project.animations[0].tracks.right_arm.transforms[0].matrix[3]).toBeCloseTo(-0.29296875);
  });

  it("reflects native cube rotations without reversing the Z axis", async () => {
    const input = {
      name: "rotated_cube.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [{
          uuid: "cube",
          name: "Cube",
          type: "cube",
          from: [12, -4, -4],
          to: [20, 4, 4],
          faces: { north: { uv: [0, 0, 8, 8] } },
        }],
        groups: [
          { uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 90] },
          { uuid: "child", name: "child", origin: [16, 0, 0], rotation: [0, 0, 0] },
        ],
        outliner: [{ uuid: "root", children: [{ uuid: "child", children: ["cube"] }] }],
        textures: [],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);

    expect(project.nodes.child.defaultMatrix[3]).toBeCloseTo(0);
    expect(project.nodes.child.defaultMatrix[7]).toBeCloseTo(0.9375);
    expect(project.nodes.child.defaultMatrix[11]).toBeCloseTo(0);
  });

  it("preserves separate native bone and direct display animation conventions", async () => {
    const input = {
      name: "shared_axes.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [
          { uuid: "cube", name: "Cube", type: "cube", from: [-4, -4, -4], to: [4, 4, 4], faces: { north: { uv: [0, 0, 8, 8] } } },
          { uuid: "item", name: "Item", type: "animated_java:vanilla_item_display", position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], visibility: true, item: "minecraft:stick" },
        ],
        groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "root", children: ["cube"] }, "item"],
        textures: [],
        animations: [{
          name: "move",
          loop: "once",
          length: 0.05,
          animators: {
            root: { name: "root", type: "bone", keyframes: [
              projectFrame("position", 0, ["8", "0", "-8"]),
              projectFrame("rotation", 0, ["45", "0", "0"]),
            ] },
            item: { name: "Item", type: "animated_java:vanilla_item_display", keyframes: [
              projectFrame("position", 0, ["8", "0", "-8"]),
              projectFrame("rotation", 0, ["45", "0", "0"]),
            ] },
          },
        }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const cubeMatrix = project.animations[0].tracks.root.transforms[0].matrix;
    const itemMatrix = project.animations[0].tracks.item.transforms[0].matrix;

    expect(cubeMatrix[3]).toBeCloseTo(-0.46875);
    expect(itemMatrix[3]).toBeCloseTo(0.5);
    expect(cubeMatrix[11]).toBeCloseTo(-0.46875);
    expect(itemMatrix[11]).toBeCloseTo(-0.5);
    expect(cubeMatrix[6]).toBeLessThan(0);
    expect(itemMatrix[6]).toBeGreaterThan(0);
  });

  it("assigns planar native cubes to player heads with a zero scale axis", async () => {
    const input = {
      name: "plane.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated_java_blueprint", format_version: "1.5.2" },
        resolution: { width: 16, height: 16 },
        elements: [{
          uuid: "plane",
          name: "Plane",
          type: "cube",
          from: [0, 0, 0],
          to: [0, 8, 8],
          faces: { east: { uv: [0, 0, 8, 8], texture: 0 } },
        }],
        outliner: [{ uuid: "root", name: "root", origin: [0, 0, 0], children: ["plane"] }],
        textures: [{ source: "data:image/png;base64,iVBORw0KGgo=" }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);

    expect(project.nodes.root.type).toBe("item_display");
    expect(project.nodes.root.type === "item_display" && project.nodes.root.playerHeadConversion?.matrix[0]).toBe(0);
    expect(project.diagnostics.map((issue) => issue.code)).not.toContain("geckolib_cube_player_head_unavailable");
  });

  it("imports textureless native player rigs", async () => {
    const input = {
      name: "textureless_player.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [{
          uuid: "head_cube",
          name: "head",
          type: "cube",
          from: [-4, 24, -4],
          to: [4, 32, 4],
          faces: { north: { uv: [8, 8, 16, 16] } },
        }],
        groups: [{ uuid: "head", name: "head", origin: [0, 24, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "head", children: ["head_cube"] }],
        textures: [],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const model = [...project.resources.entries()].find(([path]) => path.endsWith("/models/item/textureless_player/head.json"));

    expect(project.nodes.head.type === "item_display" && project.nodes.head.suggestedSkin).toEqual({ part: "head", order: 0 });
    expect(model && new TextDecoder().decode(model[1])).toContain("minecraft:block/white_concrete");
    expect([...project.resources.keys()].some((path) => path.endsWith(".png"))).toBe(false);
  });

  it("compiles presegmented native limbs entirely as player skin heads", async () => {
    const cube = (uuid: string, name: string, fromY: number, toY: number) => ({
      uuid,
      name,
      type: "cube",
      from: [4, fromY, -2],
      to: [8, toY, 2],
      faces: { north: { uv: [0, 24 - toY, 4, 24 - fromY] } },
    });
    const input = {
      name: "presegmented_player.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 64, height: 64 },
        elements: [
          cube("upper", "right_arm", 18, 24),
          cube("upper_layer", "right_sleeve", 18.25, 24),
          cube("lower", "right_arm", 12, 18),
          cube("lower_layer", "right_sleeve", 12, 17.75),
        ],
        groups: [
          { uuid: "upper_bone", name: "right_arm", origin: [6, 24, 0], rotation: [0, 0, 0] },
          { uuid: "lower_bone", name: "right_hand", origin: [6, 18, 0], rotation: [0, 0, 0] },
        ],
        outliner: [{
          uuid: "upper_bone",
          children: ["upper", "upper_layer", { uuid: "lower_bone", children: ["lower", "lower_layer"] }],
        }],
        textures: [],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const skinNodes = Object.values(project.nodes).filter((node) => node.type === "item_display" && node.suggestedSkin?.part === "right_arm");
    const [compiled] = compileImportedProject(project, { minecraftVersion: "26.2" });
    const itemNodes = Object.values(compiled.nodes).filter((node) => node.type === "item_display");

    expect(skinNodes.map((node) => node.type === "item_display" && node.suggestedSkin?.order)).toEqual([0, 1, 1, 2, 2, 3]);
    expect(Object.keys(project.nodes).some((id) => id.includes("sleeve"))).toBe(false);
    expect(itemNodes.every((node) => node.type === "item_display" && node.item_stack_snbt?.includes("minecraft:player_head") === true)).toBe(true);
  });

  it("joins presegmented skin limbs stored in sibling bones", async () => {
    const cube = (uuid: string, name: string, fromY: number, toY: number) => ({
      uuid,
      name,
      type: "cube",
      from: [0, fromY, -2],
      to: [4, toY, 2],
      faces: { north: { uv: [0, 12 - toY, 4, 12 - fromY] } },
    });
    const input = {
      name: "sibling_legs.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 64, height: 64 },
        elements: [
          cube("upper", "right_leg", 6, 12),
          cube("upper_layer", "right_pants", 6.25, 12),
          cube("lower", "right_leg", 0, 6),
          cube("lower_layer", "right_pants", 0, 5.75),
        ],
        groups: [
          { uuid: "leg", name: "right_leg", origin: [2, 12, 0], rotation: [0, 0, 0] },
          { uuid: "upper_bone", name: "upper_right_leg", origin: [0, 12, 0], rotation: [0, 0, 0] },
          { uuid: "lower_bone", name: "lower_right_leg", origin: [0, 6, 0], rotation: [0, 0, 0] },
        ],
        outliner: [{
          uuid: "leg",
          children: [
            { uuid: "upper_bone", children: ["upper", "upper_layer"] },
            { uuid: "lower_bone", children: ["lower", "lower_layer"] },
          ],
        }],
        textures: [],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const skinNodes = Object.values(project.nodes).filter((node) => node.type === "item_display" && node.suggestedSkin?.part === "right_leg");

    expect(skinNodes.map((node) => node.type === "item_display" && node.suggestedSkin?.order)).toEqual([0, 1, 1, 2, 2, 3]);
    expect(Object.keys(project.nodes).some((id) => id.includes("pants"))).toBe(false);
  });

  it("imports mixed cube, locator, and display projects", async () => {
    const input = {
      name: "mixed.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [
          { uuid: "cube", name: "Cube", type: "cube", from: [0, 0, 0], to: [8, 8, 8], faces: { north: { uv: [0, 0, 8, 8], texture: "texture" } } },
          { uuid: "locator", name: "hand", type: "locator", position: [0, 8, 0], rotation: [0, 0, 0] },
          { uuid: "label", name: "Label", type: "animated_java:text_display", position: [0, 8, 0], rotation: [0, 0, 0], scale: [1, 1, 1], visibility: true, text: { text: "Label" } },
        ],
        groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "root", children: ["cube", "locator", "label"] }],
        textures: [{ uuid: "texture", source: "data:image/png;base64,iVBORw0KGgo=" }],
        animations: [{ name: "idle", loop: "once", length: 0.05, animators: {} }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);

    expect(project.nodes.root.type).toBe("item_display");
    expect(project.nodes.root_hand.type).toBe("anchor");
    expect(project.nodes.label.type).toBe("text_display");
  });

  it("preserves multiple embedded textures and face texture references", async () => {
    const cube = (uuid: string, texture: number | string) => ({
      uuid,
      name: uuid,
      type: "cube",
      from: [0, 0, 0],
      to: [8, 8, 8],
      faces: { north: { uv: [0, 0, 8, 8], texture } },
    });
    const input = {
      name: "textures.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [cube("first", 0), cube("second", "texture_b")],
        groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "root", children: ["first", "second"] }],
        textures: [
          { uuid: "texture_a", source: "data:image/png;base64,iVBORw0KGgo=" },
          { uuid: "texture_b", source: "data:image/png;base64,iVBORw0KGgo=" },
        ],
        animations: [{ name: "idle", loop: "once", length: 0.05, animators: {} }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const paths = [...project.resources.keys()];
    const models = [...project.resources.entries()]
      .filter(([path]) => path.includes("/models/item/") && path.endsWith(".json"))
      .map(([, bytes]) => new TextDecoder().decode(bytes));

    expect(paths.some((path) => path.endsWith("/texture_0.png"))).toBe(true);
    expect(paths.some((path) => path.endsWith("/texture_1.png"))).toBe(true);
    expect(models.some((model) => model.includes('"texture": "#layer1"'))).toBe(true);
  });

  it("creates an idle animation for static native projects", async () => {
    const project = await animatedJavaJsonAdapter.import(nativeProject({
      elements: [{
        uuid: "item",
        name: "Item",
        type: "animated_java:vanilla_item_display",
        position: [0, 0, 0],
        rotation: [0, 0, 0],
        scale: [1, 1, 1],
        visibility: true,
        item: "minecraft:stick",
      }],
      outliner: ["item"],
      animations: [],
    }));

    expect(project.nodes.item.type).toBe("item_display");
    expect(project.animations).toHaveLength(1);
    expect(project.animations[0].id).toBe("idle");
  });

  it("applies parent animation, easing, start delay, blend weight, hold, and visibility to displays", async () => {
    const input = {
      name: "display_animation.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [{
          uuid: "item",
          name: "Item",
          type: "animated_java:vanilla_item_display",
          position: [16, 0, 0],
          rotation: [0, 0, 0],
          scale: [1, 1, 1],
          visibility: true,
          item: "minecraft:stick",
        }],
        groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "root", children: ["item"] }],
        textures: [],
        animations: [{
          name: "turn",
          loop: "hold",
          length: 0.1,
          start_delay: 0.05,
          blend_weight: 0.5,
          animators: {
            root: { keyframes: [
              projectFrame("rotation", 0, ["0", "0", "0"]),
              {
                ...projectFrame("rotation", 0.1, ["0", "0", "90"]),
                data_points: [{ x: "0", y: "0", z: "45" }, { x: "0", y: "0", z: "90" }],
                easing: "easeInOutExpo",
              },
            ] },
            item: { keyframes: [{ channel: "visibility", time: 0.05, interpolation: "step", data_points: [{ x: 0 }] }] },
          },
        }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const animation = project.animations[0];
    const finalMatrix = animation.tracks.item.transforms.at(-1)!.matrix;

    expect(animation.loop).toBe("hold");
    expect(animation.durationTicks).toBe(3);
    expect(animation.tracks.item.visibility).toEqual([{ tick: 2, visible: false }]);
    expect(finalMatrix[3]).toBeCloseTo(-Math.SQRT1_2);
    expect(finalMatrix[7]).toBeCloseTo(Math.SQRT1_2);
  });

  it("converts native functions and display variants to events and NBT tracks", async () => {
    const variantId = "variant-alt";
    const input = {
      name: "config.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        blueprint_settings: { custom_summon_commands: "/say summoned" },
        elements: [{
          uuid: "item",
          name: "Item",
          type: "animated_java:vanilla_item_display",
          position: [0, 0, 0],
          rotation: [0, 0, 0],
          scale: [1, 1, 1],
          visibility: true,
          item: "minecraft:stick",
          configs: {
            default: { billboard: "vertical", glowing: true, on_tick_function: "say ticking" },
            variants: { [variantId]: { glowing: false, invisible: true } },
          },
        }, { uuid: "interaction", name: "Hitbox", type: "animated_java:interaction" }],
        groups: [{
          uuid: "root",
          name: "root",
          origin: [0, 0, 0],
          rotation: [0, 0, 0],
          configs: { default: { shadow_radius: 2 }, variants: { [variantId]: { shadow_radius: 1 } } },
        }],
        outliner: [{ uuid: "root", children: ["item"] }],
        textures: [],
        variants: { default: { uuid: "default", name: "default" }, list: [{ uuid: variantId, name: "alt", excluded_nodes: [], texture_map: { old: "new" }, on_apply_function: "/say applied" }] },
        collections: [{}],
        animation_controllers: [{}],
        animations: [{
          name: "configured",
          loop: "once",
          length: 0.1,
          animators: { effects: { type: "effect", keyframes: [
            { channel: "function", time: 0.05, data_points: [{ function: "/say hello", repeat: false }] },
            { channel: "variant", time: 0.1, data_points: [{ variant: variantId }] },
          ] } },
        }],
      })),
    };

    const project = await animatedJavaJsonAdapter.import(input);
    const node = project.nodes.item;
    const animation = project.animations[0];

    expect(node.type !== "anchor" && node.entityNbt).toContain('billboard:"vertical"');
    expect(node.type !== "anchor" && node.entityNbt).toContain("shadow_radius:2");
    expect(animation.events.timeline).toContainEqual(expect.objectContaining({ tick: 1, commands: ["say hello"] }));
    expect(animation.events.start).toContainEqual(expect.objectContaining({ commands: ["say summoned"] }));
    expect(animation.events.timeline).toContainEqual(expect.objectContaining({ tick: 2, commands: ["say applied"] }));
    expect(animation.tracks.item.visibility).toContainEqual({ tick: 2, visible: false });
    expect(animation.tracks.item.nbt).toContainEqual({ tick: 2, value: expect.stringContaining("Glowing:0b") });
    expect(animation.tracks.item.nbt).toContainEqual({ tick: 2, value: expect.stringContaining("shadow_radius:1") });
    expect(project.diagnostics.map((diagnostic) => diagnostic.code)).toEqual(expect.arrayContaining([
      "unsupported_animated_java_interaction",
      "unsupported_animated_java_animation_controllers",
      "unsupported_animated_java_collections",
      "unsupported_animated_java_tick_function",
      "animated_java_variant_texture_map_ignored",
    ]));
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
      textures: {
        skin: {
          type: "custom",
          base64_string: "iVBORw0KGgo=",
          animation: { frametime: 3, interpolate: true, frames: [0, { index: 1, time: 5 }] },
        },
      },
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
      "assets/demo/textures/item/rig/skin.png.mcmeta",
      "assets/demo/models/item/rig/body.json",
      "assets/demo/items/rig/body.json",
    ]);
    expect(new TextDecoder().decode(project.resources.get("assets/demo/textures/item/rig/skin.png.mcmeta"))).toBe(
      `${JSON.stringify({ animation: { frametime: 3, interpolate: true, frames: [0, { index: 1, time: 5 }] } }, null, 2)}\n`,
    );
  });

  it("converts declared and signature-detected JPEG textures to PNG with browser image APIs", async () => {
    const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const close = vi.fn();
    const drawImage = vi.fn();
    const toBlob = vi.fn((callback: BlobCallback, type?: string) => {
      expect(type).toBe("image/png");
      callback(new Blob([pngBytes], { type: "image/png" }));
    });
    vi.stubGlobal("createImageBitmap", vi.fn(async () => ({ width: 2, height: 1, close }) as unknown as ImageBitmap));
    vi.stubGlobal("document", {
      createElement: vi.fn(() => ({ width: 0, height: 0, getContext: () => ({ drawImage }), toBlob })),
    });

    try {
      for (const mimeType of ["image/jpeg", undefined]) {
        const input = rawBlueprint({
          format_version: 1,
          settings: { id: "demo:jpeg" },
          textures: { photo: { type: "custom", ...(mimeType ? { mime_type: mimeType } : {}), base64_string: "/9j/AA==" } },
          nodes: {
            body: {
              type: "bone",
              elements: [{
                from: [0, 0, 0], to: [1, 1, 1], rotation: [0, 0, 0],
                faces: { north: { uv: [0, 0, 16, 16], texture_provider: { type: "texture", texture: "photo" } } },
              }],
            },
          },
          animations: { idle: { loop_mode: { type: "once" }, length: 0.05 } },
        });

        const project = await animatedJavaJsonAdapter.import(input);
        expect(project.resources.get("assets/demo/textures/item/jpeg/photo.png")).toEqual(pngBytes);
      }
      expect(drawImage).toHaveBeenCalledTimes(2);
      expect(close).toHaveBeenCalledTimes(2);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("converts GIF frames and timings into a PNG atlas with Minecraft animation metadata", async () => {
    const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2]);
    const contexts = Array.from({ length: 3 }, () => ({
      clearRect: vi.fn(),
      drawImage: vi.fn(),
      getImageData: vi.fn(() => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 })),
      putImageData: vi.fn(),
    }));
    const canvases = contexts.map((context) => ({
      width: 0,
      height: 0,
      getContext: () => context,
      toBlob: (callback: BlobCallback) => callback(new Blob([pngBytes], { type: "image/png" })),
    }));
    let canvasIndex = 0;
    vi.stubGlobal("ImageData", class {
      constructor(readonly data: Uint8ClampedArray, readonly width: number, readonly height: number) {}
    });
    vi.stubGlobal("document", { createElement: vi.fn(() => canvases[canvasIndex++]) });

    try {
      const input = rawBlueprint({
        format_version: 1,
        settings: { id: "demo:gif" },
        textures: {
          effect: {
            type: "custom",
            mime_type: "image/gif",
            base64_string: "R0lGODlhAQABAIAAAP///wAAACH5BAEFAAAALAAAAAABAAEAAAICRAEAIfkEAQoAAAAsAAAAAAEAAQAAAgJEAQA7",
          },
        },
        nodes: {
          body: {
            type: "bone",
            elements: [{
              from: [0, 0, 0], to: [1, 1, 1], rotation: [0, 0, 0],
              faces: { north: { uv: [0, 0, 16, 16], texture_provider: { type: "texture", texture: "effect" } } },
            }],
          },
        },
        animations: { idle: { loop_mode: { type: "once" }, length: 0.05 } },
      });

      const project = await animatedJavaJsonAdapter.import(input);

      expect(project.resources.get("assets/demo/textures/item/gif/effect.png")).toEqual(pngBytes);
      expect(JSON.parse(new TextDecoder().decode(project.resources.get("assets/demo/textures/item/gif/effect.png.mcmeta")))).toEqual({
        animation: { width: 1, height: 1, frames: [{ index: 0, time: 1 }, { index: 1, time: 2 }] },
      });
      expect(canvases[2]).toMatchObject({ width: 2, height: 1 });
      expect(contexts[2].drawImage).toHaveBeenNthCalledWith(1, canvases[0], 0, 0);
      expect(contexts[2].drawImage).toHaveBeenNthCalledWith(2, canvases[0], 1, 0);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("rejects malformed GIF textures", async () => {
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:gif" },
      textures: { effect: { type: "custom", base64_string: "R0lGODlh" } },
      nodes: { item: { type: "item_display" } },
      animations: { idle: { loop_mode: { type: "once" }, length: 0.05 } },
    });

    await expect(animatedJavaJsonAdapter.import(input)).rejects.toMatchObject({
      code: "animated_java_gif_conversion_failed",
      sourcePath: "textures.effect.base64_string",
    });
  });

  it("rejects unsupported custom texture formats", async () => {
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:webp" },
      textures: { photo: { type: "custom", mime_type: "image/webp", base64_string: "UklGRg==" } },
      nodes: { item: { type: "item_display" } },
      animations: { idle: { loop_mode: { type: "once" }, length: 0.05 } },
    });

    await expect(animatedJavaJsonAdapter.import(input)).rejects.toMatchObject({
      code: "unsupported_animated_java_texture_format",
      sourcePath: "textures.photo.mime_type",
    });
  });

  it("converts texture palette keyframes into item NBT tracks", async () => {
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:rig" },
      textures: {
        red: { type: "reference", resource_location: "minecraft:block/red_wool" },
        blue: { type: "reference", resource_location: "minecraft:block/blue_wool" },
      },
      texture_palettes: {
        skin: { active_state: "red", states: { red: { texture: "red" }, blue: { texture: "blue" } } },
      },
      nodes: {
        body: {
          type: "bone",
          display_properties: { is_enchanted: true },
          elements: [{
            from: [0, 0, 0], to: [1, 1, 1], rotation: [0, 0, 0],
            faces: { north: { uv: [0, 0, 16, 16], texture_provider: { type: "texture_palette", texture_palette: "skin" } } },
          }],
        },
      },
      animations: {
        swap: {
          loop_mode: { type: "once" },
          length: 0.05,
          global_keyframes: { texture: { "0.0": { skin: "red" }, "0.05": { skin: "blue" } } },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "demo" });

    expect(new TextDecoder().decode(project.resources.get("assets/demo/models/item/rig/body.json"))).toContain("minecraft:block/red_wool");
    expect(new TextDecoder().decode(project.resources.get("assets/demo/models/item/rig/body_palette_1.json"))).toContain("minecraft:block/blue_wool");
    expect(project.nodes.body.type === "item_display" && project.nodes.body.itemStackSnbt).toBe(
      '{id:"minecraft:paper",count:1,components:{"minecraft:item_model":"demo:rig/body","minecraft:enchantment_glint_override":1b}}',
    );
    expect(animation.timeline.tracks.body.nbt).toEqual([
      { time: "0t", value: '{item:{id:"minecraft:paper",count:1,components:{"minecraft:item_model":"demo:rig/body","minecraft:enchantment_glint_override":1b}}}' },
      { time: "1t", value: '{item:{id:"minecraft:paper",count:1,components:{"minecraft:item_model":"demo:rig/body_palette_1","minecraft:enchantment_glint_override":1b}}}' },
    ]);
  });

  it("converts API event keyframes into namespaced callbacks", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      attack: {
        loop_mode: { type: "once" },
        start_delay: "0.05",
        length: 0.1,
        global_keyframes: {
          event: {
            "0.0": { events: ["swing", "trail"] },
            "0.1": { events: ["finish"] },
          },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "callbacks" });

    expect(animation.timeline.events?.timeline).toEqual([
      {
        time: "1t",
        source: { type: "player" },
        origin: { type: "root" },
        commands: [],
        callbacks: [{ name: "demo:swing" }, { name: "demo:trail" }],
      },
      {
        time: "2t",
        source: { type: "player" },
        origin: { type: "root" },
        commands: [],
        callbacks: [{ name: "demo:finish" }],
      },
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

  it("assigns lower limb slices to a matching animated joint", async () => {
    const face = { uv: [0, 0, 4, 12], texture_provider: { type: "texture", texture: "skin" } };
    const identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
    const lowerJoint = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -0.375, 0, 1];
    const input = rawBlueprint({
      format_version: 1,
      settings: { id: "demo:jointed_rig" },
      textures: { skin: { type: "custom", base64_string: "iVBORw0KGgo=" } },
      nodes: {
        right_arm: {
          type: "bone",
          default_transformation: { matrix: identity },
          elements: [{ from: [8, 0, 6], to: [12, 12, 10], rotation: [0, 0, 0], faces: { north: face } }],
        },
        right_forearm: {
          type: "bone",
          default_transformation: { matrix: lowerJoint },
          elements: [],
        },
      },
      animations: {
        bend: {
          loop_mode: { type: "once" },
          length: 0.05,
          node_keyframes: {
            right_arm: { rotation: { "0.0": baked(["0", "0", "0"]), "0.05": baked(["0", "0", "0"]) } },
            right_forearm: { rotation: { "0.0": baked(["0", "0", "0"]), "0.05": baked(["45", "0", "0"]) } },
          },
        },
      },
    });

    const project = await animatedJavaJsonAdapter.import(input);

    expect(Object.keys(project.nodes)).toEqual([
      "right_arm", "right_arm_2", "right_arm_3", "right_arm_4", "right_arm_5", "right_arm_6", "right_forearm",
    ]);
    expect(["right_arm", "right_arm_2", "right_arm_3", "right_arm_4", "right_arm_5", "right_arm_6"].map((id) => {
      const node = project.nodes[id];
      return node.type === "item_display" ? node.suggestedSkin?.order : undefined;
    })).toEqual([0, 1, 1, 2, 2, 3]);
    expect(project.nodes.right_arm_2.type === "item_display" && project.nodes.right_arm_2.skinAssignmentGroup).toBe("right_arm_1");
    expect(project.nodes.right_arm_3.type === "item_display" && project.nodes.right_arm_3.skinAssignmentGroup).toBe("right_arm_1");
    expect(project.nodes.right_arm_3.type === "item_display"
      && Math.abs(project.nodes.right_arm_3.playerHeadConversion!.matrix[6])).toBeGreaterThan(0.1);
    expect(project.animations[0].tracks.right_arm_4.transforms[1].matrix)
      .not.toEqual(project.animations[0].tracks.right_arm.transforms[1].matrix);
    expect(project.animations[0].tracks.right_arm_6.transforms)
      .toEqual(project.animations[0].tracks.right_arm_4.transforms);
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
        global_keyframes: { event: { "0.05": { events: ["runtime_event"] } } },
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
    expect(compiled.timeline.events?.timeline?.[0]).toMatchObject({
      time: "1t",
      callbacks: [{ name: "demo:runtime_event" }],
    });
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

  it("rejects a non-boolean bone enchantment flag", async () => {
    const input = blueprint({
      body: { type: "bone", display_properties: { is_enchanted: "yes" } },
    }, { idle: { loop_mode: { type: "once" }, length: 0.05 } });

    await expect(animatedJavaJsonAdapter.import(input)).rejects.toThrow(
      "nodes.body.display_properties.is_enchanted must be a boolean",
    );
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
