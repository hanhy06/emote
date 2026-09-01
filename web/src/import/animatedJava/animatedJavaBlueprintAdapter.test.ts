import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../test/compileImportedFixture";
import { animatedJavaBlueprintAdapter } from "./animatedJavaBlueprintAdapter";

const encoder = new TextEncoder();

describe("animatedJavaBlueprintAdapter", () => {
  it("accepts Animated Java project files", async () => {
    expect(animatedJavaBlueprintAdapter.extensions).toEqual(["ajblueprint"]);
    expect(animatedJavaBlueprintAdapter.label).toBe("Animated Java project");
    expect((await animatedJavaBlueprintAdapter.probe(rawBlueprint({
      meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
      resolution: { width: 16, height: 16 },
      elements: [],
      groups: [],
      outliner: [],
      textures: [],
      animations: [],
    }))).confidence).toBe(100);
  });

  it("rejects the removed Plugin Blueprint JSON format", async () => {
    const input = {
      name: "plugin-blueprint.json",
      bytes: encoder.encode(JSON.stringify({
        format_version: 1,
        settings: { id: "demo:legacy" },
        nodes: {},
        animations: {},
      })),
    };

    expect((await animatedJavaBlueprintAdapter.probe(input)).confidence).toBe(0);
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

    const project = await animatedJavaBlueprintAdapter.import(input);

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

    const project = await animatedJavaBlueprintAdapter.import(input);

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

    const project = await animatedJavaBlueprintAdapter.import(input);

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

    const project = await animatedJavaBlueprintAdapter.import(input);
    const cubeMatrix = project.animations[0].tracks.root.transforms[0].matrix;
    const itemMatrix = project.animations[0].tracks.item.transforms[0].matrix;

    expect(cubeMatrix[3]).toBeCloseTo(-0.46875);
    expect(itemMatrix[3]).toBeCloseTo(0.46875);
    expect(cubeMatrix[11]).toBeCloseTo(-0.46875);
    expect(itemMatrix[11]).toBeCloseTo(-0.46875);
    expect(cubeMatrix[6]).toBeLessThan(0);
    expect(itemMatrix[6]).toBeGreaterThan(0);
  });

  it("merges mixed native Molang runtimes without moving displays to the origin", async () => {
    const input = {
      name: "mixed_runtime.ajblueprint",
      bytes: encoder.encode(JSON.stringify({
        meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
        resolution: { width: 16, height: 16 },
        elements: [
          { uuid: "cube", name: "Cube", type: "cube", from: [-4, -4, -4], to: [4, 4, 4], faces: { north: { uv: [0, 0, 8, 8] } } },
          { uuid: "item", name: "Item", type: "animated_java:vanilla_item_display", position: [16, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], visibility: true, item: "minecraft:stick" },
        ],
        groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0] }],
        outliner: [{ uuid: "root", children: ["cube"] }, "item"],
        textures: [],
        animations: [{
          name: "runtime",
          loop: "once",
          length: 0.1,
          animators: {
            root: { name: "root", type: "bone", keyframes: [projectFrame("position", 0.05, ["v.bone_x", "0", "0"])] },
            item: { name: "Item", type: "animated_java:vanilla_item_display", keyframes: [projectFrame("position", 0.05, ["v.item_x", "0", "0"])] },
          },
        }],
      })),
    };

    const project = await animatedJavaBlueprintAdapter.import(input);
    const [animation] = compileImportedProject(project, { minecraftVersion: "26.2", namespace: "runtime" });

    expect(animation.nodes.root).toBeDefined();
    expect(animation.nodes.item).toBeDefined();
    expect(animation.timeline.tracks.root_z.position).toBeDefined();
    expect(animation.nodes.aj_item_x.transform.scale).toEqual([0.9375, 0.9375, 0.9375]);
    expect(animation.timeline.tracks.aj_item_z.position?.[0].value).toEqual([-0.9375, 0, 0]);
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

    const project = await animatedJavaBlueprintAdapter.import(input);

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

    const project = await animatedJavaBlueprintAdapter.import(input);
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

    const project = await animatedJavaBlueprintAdapter.import(input);
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

    const project = await animatedJavaBlueprintAdapter.import(input);
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

    const project = await animatedJavaBlueprintAdapter.import(input);

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

    const project = await animatedJavaBlueprintAdapter.import(input);
    const paths = [...project.resources.keys()];
    const models = [...project.resources.entries()]
      .filter(([path]) => path.includes("/models/item/") && path.endsWith(".json"))
      .map(([, bytes]) => new TextDecoder().decode(bytes));

    expect(paths.some((path) => path.endsWith("/texture_0.png"))).toBe(true);
    expect(paths.some((path) => path.endsWith("/texture_1.png"))).toBe(true);
    expect(models.some((model) => model.includes('"texture": "#layer1"'))).toBe(true);
  });

  it("creates an idle animation for static native projects", async () => {
    const project = await animatedJavaBlueprintAdapter.import(nativeProject({
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

    const project = await animatedJavaBlueprintAdapter.import(input);
    const animation = project.animations[0];
    const finalMatrix = animation.tracks.item.transforms.at(-1)!.matrix;

    expect(animation.loop).toBe("hold");
    expect(animation.durationTicks).toBe(3);
    expect(animation.tracks.item.visibility).toEqual([{ tick: 2, visible: false }]);
    expect(finalMatrix[3]).toBeCloseTo(-Math.SQRT1_2 * 0.9375);
    expect(finalMatrix[7]).toBeCloseTo(Math.SQRT1_2 * 0.9375);
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

    const project = await animatedJavaBlueprintAdapter.import(input);
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
});

function rawBlueprint(value: unknown) {
  return { name: "project.ajblueprint", bytes: encoder.encode(JSON.stringify(value)) };
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
