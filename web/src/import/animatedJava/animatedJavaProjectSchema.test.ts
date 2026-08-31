import { describe, expect, it } from "vitest";
import { requireAnimatedJavaProject } from "./animatedJavaProjectSchema";

describe("Animated Java project schema", () => {
  it("accepts v1.10 mixed elements and non-transform keyframes", () => {
    const project = requireAnimatedJavaProject({
      meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
      resolution: { width: 64, height: 64 },
      blueprint_settings: { id: "demo:rig" },
      elements: [
        {
          uuid: "cube",
          name: "Cube",
          type: "cube",
          from: [0, 0, 0],
          to: [8, 8, 8],
          faces: { north: { uv: [0, 0, 8, 8], texture: "texture" } },
        },
        { uuid: "locator", name: "hand", type: "locator", position: [0, 8, 0], rotation: [0, 0, 0] },
        {
          uuid: "text",
          name: "Label",
          type: "animated_java:text_display",
          position: [0, 0, 0],
          rotation: [0, 0, 0],
          scale: [1, 1, 1],
          visibility: true,
          text: { text: "Label" },
        },
        { uuid: "interaction", name: "Hitbox", type: "animated_java:interaction" },
      ],
      groups: [{ uuid: "root", name: "root", origin: [0, 0, 0], rotation: [0, 0, 0], configs: { default: { enchanted: true } } }],
      outliner: [{ uuid: "root", children: ["cube", "locator", "text", "interaction"] }],
      textures: [{ uuid: "texture", source: "data:image/png;base64,iVBORw0KGgo=" }],
      variants: { default: { name: "Default" } },
      collections: [],
      animations: [{
        name: "wave",
        length: 1,
        loop: "hold",
        blend_weight: 0.5,
        start_delay: 0.1,
        animators: {
          effects: {
            type: "effect",
            keyframes: [{
              channel: "function",
              time: 0,
              data_points: [{ function: "say hello", execute_condition: "1" }],
            }],
          },
        },
      }],
      animation_controllers: [],
    });

    expect(project.elements.map((element) => element.type)).toEqual([
      "cube",
      "locator",
      "animated_java:text_display",
      "animated_java:interaction",
    ]);
    expect(project.animations[0].animators.effects.keyframes[0].data_points[0].function).toBe("say hello");
  });

  it("accepts static projects without animations", () => {
    expect(requireAnimatedJavaProject({
      meta: { format: "animated-java:format/blueprint", format_version: "1.10.2" },
      resolution: { width: 16, height: 16 },
      elements: [],
      groups: [],
      outliner: [],
      textures: [],
      animations: [],
    }).animations).toEqual([]);
  });
});

