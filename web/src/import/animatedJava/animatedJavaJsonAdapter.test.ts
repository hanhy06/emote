import { describe, expect, it } from "vitest";
import { compileImportedProject } from "../../compiler/animationCompiler";
import { serializeEmoteAnimation } from "../../format/serializer";
import { animatedJavaJsonAdapter } from "./animatedJavaJsonAdapter";

const encoder = new TextEncoder();

describe("animatedJavaJsonAdapter", () => {
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

  it("rejects raw nonlinear Animated Java tracks", async () => {
    const input = blueprint({ item: { type: "item_display" } }, {
      raw: {
        loop_mode: { type: "once" },
        length: 1,
        node_keyframes: { item: { position: { "0.0": { value: ["0", "0", "0"], interpolation: { type: "linear", easing: "ease_in_quad" } } } } },
      },
    });
    await expect(animatedJavaJsonAdapter.import(input)).rejects.toThrow("baked animations");
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

function baked(value: string[]) {
  return { value, interpolation: { type: "linear", easing: "linear" } };
}
